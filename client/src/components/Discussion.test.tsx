import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Discussion } from "./Discussion";
import type { ChatMessage } from "../api/contracts";
import { observerProjection } from "../test/fixtures";

const player = { ...observerProjection, viewer: { player: "alice", role: "player" as const, canAct: false } };
const message = { id: "durable", player: "alice", time: 123, message: "Hello table", "client-id": "delivery" };
const compose = () => { fireEvent.change(screen.getByRole("textbox", { name: "Message" }), { target: { value: message.message } }); fireEvent.submit(screen.getByRole("form", { name: "Send a message" })); };
describe("Discussion", () => {
  it("sends the lifetime of the rendered table with a new delivery and its retry", async () => {
    const send = vi.fn().mockRejectedValue(new Error("offline"));
    render(<Discussion projection={{ ...player, instanceId: "original-table" }} send={send} />);
    compose();
    await screen.findByRole("button", { name: "Retry message" });
    fireEvent.click(screen.getByRole("button", { name: "Retry message" }));
    await screen.findByRole("button", { name: "Retry message" });
    expect(send).toHaveBeenCalledWith("pond-life", message.message, expect.any(String), "original-table");
    expect(send.mock.calls[1]).toEqual(send.mock.calls[0]);
  });
  it("discards an old same-name delivery and ignores its late acknowledgement after replacement", async () => {
    let resolve!: (message: ChatMessage) => void;
    const send = vi.fn((_game: string, _text: string, _id: string) => new Promise<ChatMessage>(r => { resolve = r; }));
    const { rerender } = render(<Discussion projection={{ ...player, instanceId: "original-table" }} send={send} />);
    compose();
    const ack = { ...message, "client-id": send.mock.calls[0][2] };
    rerender(<Discussion projection={{ ...player, instanceId: "replacement-table" }} send={send} />);
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue("");
    expect(screen.getByRole("textbox", { name: "Message" })).not.toHaveAttribute("readonly");
    await act(async () => resolve(ack));
    expect(screen.queryByText(message.message)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Retry message" })).not.toBeInTheDocument();
  });
  it("renders initial messages once per durable ID", () => {
    render(<Discussion projection={{ ...player, chat: [message, message] }} />);
    expect(screen.getAllByText(message.message)).toHaveLength(1);
  });
  it("retains the draft on failure and retries with the same client ID", async () => {
    const send = vi.fn().mockRejectedValueOnce(new Error("offline")).mockImplementationOnce(async (_g, text, id) => ({ ...message, message: text, "client-id": id }));
    render(<Discussion projection={player} send={send} />);
    compose();
    await screen.findByRole("button", { name: "Retry message" });
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(message.message);
    fireEvent.click(screen.getByRole("button", { name: "Retry message" }));
    await waitFor(() => expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(""));
    expect(send.mock.calls[1]).toEqual(send.mock.calls[0]);
    expect(screen.getAllByText(message.message)).toHaveLength(1);
  });
  it("clears only after a matching authoritative confirmation, including a live snapshot", async () => {
    let resolve!: (message: ChatMessage) => void;
    const send = vi.fn((_game: string, _message: string, _clientId: string) => new Promise<ChatMessage>(r => { resolve = r; }));
    const { rerender } = render(<Discussion projection={player} send={send} />);
    compose();
    rerender(<Discussion projection={{ ...player, chat: [message] }} send={send} />);
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(message.message);
    const acknowledgement = { ...message, "client-id": send.mock.calls[0][2] };
    rerender(<Discussion projection={{ ...player, chat: [acknowledgement] }} send={send} />);
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue("");
    await act(async () => resolve(acknowledgement));
    expect(screen.getAllByText(message.message)).toHaveLength(1);
  });
  it("does not accept a mismatched HTTP acknowledgement", async () => {
    render(<Discussion projection={player} send={async () => message} />);
    compose();
    await screen.findByRole("button", { name: "Retry message" });
    expect(screen.getByRole("textbox", { name: "Message" })).toHaveValue(message.message);
  });
  it("prevents observer posting and drops cached private messages on membership revocation", async () => {
    const lobby = { owner: "alice", visibility: "private" as const, member: true, readiness: {}, canStart: false };
    const send = vi.fn(async (_g, _t, id) => ({ ...message, "client-id": id }));
    const { rerender } = render(<Discussion projection={{ ...player, status: "waiting", lobby }} send={send} />);
    compose();
    await screen.findByText(message.message);
    rerender(<Discussion projection={{ ...player, status: "waiting", lobby: { ...lobby, member: false }, viewer: { player: "alice", role: "observer", canAct: false } }} send={send} />);
    expect(screen.queryByText(message.message)).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
  });
});
