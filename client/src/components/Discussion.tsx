import { createContext, useContext, useEffect, useId, useRef, useState } from "react";
import type { ReactNode } from "react";
import { postChat } from "../api/client";
import type { ChatMessage, GameProjection } from "../api/contracts";

export type ChatSender = (game: string, message: string, clientId: string, instanceId?: string) => Promise<ChatMessage>;
const sendChat: ChatSender = (game, message, clientId, instanceId) => postChat(game, message, clientId, fetch, instanceId);
type Delivery = { message: string; clientId: string; instanceId?: string };
function messageKey(message: ChatMessage) {
  // Pre-ID saved discussions remain readable; new messages always have a durable ID.
  return message.id ?? JSON.stringify([message.player, message.time, message.message]);
}

function permissions(projection: GameProjection) {
  const canSend = projection.status === "waiting" ? Boolean(projection.lobby?.member) : projection.viewer.role === "player";
  const canRead = projection.status !== "waiting" || Boolean(projection.lobby?.member);
  return { canSend, canRead, scope: JSON.stringify([projection.gameId, projection.instanceId ?? null, projection.viewer.player, canRead, canSend]) };
}

const DiscussionContext = createContext<ReturnType<typeof useDiscussionState> | null>(null);

// The owner survives lobby -> board, but never an account/game/permission change.
// A keyed lifetime also fences late replies when a previous scope is revisited.
export function DiscussionProvider({ projection, children }: { projection: GameProjection; children: ReactNode }) {
  return <ScopedDiscussionProvider key={permissions(projection).scope} projection={projection}>{children}</ScopedDiscussionProvider>;
}

function ScopedDiscussionProvider({ projection, children }: { projection: GameProjection; children: ReactNode }) {
  const state = useDiscussionState(projection);
  return <DiscussionContext.Provider value={state}>{children}</DiscussionContext.Provider>;
}

function useDiscussionState(projection: GameProjection) {
  const { canSend, canRead, scope } = permissions(projection);
  const [draft, setDraft] = useState("");
  const [confirmed, setConfirmed] = useState<ChatMessage[]>([]);
  const [status, setStatus] = useState<"ready" | "sending" | "error">("ready");
  const [feedback, setFeedback] = useState("");
  const pending = useRef<Delivery | null>(null);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(() => () => {
    pending.current = null;
    clearTimeout(timer.current);
  }, []);

  function acknowledge(message: ChatMessage) {
    if (!pending.current || !message.id || message["client-id"] !== pending.current.clientId
      || message.message !== pending.current.message || message.player.toLowerCase() !== projection.viewer.player?.toLowerCase()) return false;
    clearTimeout(timer.current);
    pending.current = null;
    setDraft(""); setStatus("ready"); setFeedback("Message sent.");
    return true;
  }
  useEffect(() => {
    if (!canRead) return;
    for (const message of projection.chat as unknown as ChatMessage[]) acknowledge(message);
  }, [projection.chat, canRead]);

  async function submit(send: ChatSender, maxLength: number) {
    if (!canSend || !projection.viewer.player || status === "sending") return;
    const delivery = pending.current ?? { message: draft, clientId: crypto.randomUUID(), instanceId: projection.instanceId ?? undefined };
    if (!delivery.message.trim() || delivery.message.length > maxLength) return;
    pending.current = delivery;
    setStatus("sending"); setFeedback("Sending…");
    clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      if (pending.current === delivery) {
        setStatus("error"); setFeedback("Confirmation is taking longer than expected. Retry this message safely.");
      }
    }, 10000);
    try {
      const message = await (delivery.instanceId
        ? send(projection.gameId, delivery.message, delivery.clientId, delivery.instanceId)
        : send(projection.gameId, delivery.message, delivery.clientId));
      if (pending.current !== delivery) return;
      if (!acknowledge(message)) throw new Error("The server did not confirm this message.");
      setConfirmed(current => [...current.filter(item => messageKey(item) !== messageKey(message)), message]);
    } catch (error) {
      if (pending.current !== delivery) return;
      clearTimeout(timer.current);
      setStatus("error");
      setFeedback(error instanceof Error ? error.message : "Message not confirmed. Please retry.");
    }
  }
  const messages = canRead ? [...new Map([
    ...confirmed, ...projection.chat as unknown as ChatMessage[],
  ].map(message => [messageKey(message), message])).values()].sort((a, b) => a.time - b.time) : [];
  return { scope, canSend, canRead, draft, setDraft, status, feedback, readOnly: Boolean(pending.current), messages, submit };
}

export function Discussion({ projection, send = sendChat, maxLength = 1000 }: {
  projection: GameProjection; send?: ChatSender; maxLength?: number;
}) {
  const shared = useContext(DiscussionContext);
  // Standalone pages, component tests and fixtures need no surrounding provider.
  const content = <DiscussionContent send={send} maxLength={maxLength} />;
  return shared?.scope === permissions(projection).scope ? content : <DiscussionProvider projection={projection}>{content}</DiscussionProvider>;
}

function DiscussionContent({ send, maxLength }: { send: ChatSender; maxLength: number }) {
  const { canSend, canRead, draft, setDraft, status, feedback, readOnly, messages, submit } = useContext(DiscussionContext)!;
  const inputId = useId();
  return <section className="discussion" aria-label="Discussion">
    <div className="section-label">Discussion</div><h3>Around the table</h3>
    {messages.length ? <ol className="messages" aria-live="polite">{messages.map(message => <li key={messageKey(message)}><strong>{message.player}</strong><p>{message.message}</p></li>)}</ol> : <p className="muted">{canRead ? "Start the conversation." : "Join this table to read and send messages."}</p>}
    {canSend ? <form aria-label="Send a message" onSubmit={event => { event.preventDefault(); void submit(send, maxLength); }}>
      <label htmlFor={inputId}>Message</label>
      <div className="compose"><input id={inputId} value={draft} maxLength={maxLength} readOnly={readOnly} placeholder="Write to the table…" onChange={event => setDraft(event.target.value)} />
        <button className="primary" type="submit" disabled={status === "sending" || !draft.trim()}>{status === "error" ? "Retry message" : "Send"}</button></div>
      <p role="status" className="feedback">{feedback}</p>
    </form> : <p className="muted">Watching only · seated players can post messages.</p>}
  </section>;
}
