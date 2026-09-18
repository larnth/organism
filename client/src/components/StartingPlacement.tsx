import { useState } from "react";
import type { LegalAction } from "../api/contracts";
import { ElementGlyph } from "./ElementGlyph";

export type Placement = { coordinate: [string, number]; type: string };
export function startingPlacement(action: LegalAction): Placement[] {
  const option = action.options?.[0];
  const spaces = option && typeof option === "object" && !Array.isArray(option) ? option.spaces : null;
  if (!Array.isArray(spaces)) return [];
  return spaces.flatMap(entry => {
    if (!Array.isArray(entry) || !Array.isArray(entry[0]) || typeof entry[0][0] !== "string" || typeof entry[0][1] !== "number" || typeof entry[1] !== "string") return [];
    return [{ coordinate: [entry[0][0], entry[0][1]] as [string, number], type: entry[1] }];
  }).sort((a, b) => a.coordinate[0].localeCompare(b.coordinate[0]) || a.coordinate[1] - b.coordinate[1]);
}

export function StartingPlacement({ actions, disabled, onAction, onPreview }: {
  actions: LegalAction[]; disabled: boolean; onAction: (actions: LegalAction[]) => void;
  onPreview: (placement: Placement[]) => void;
}) {
  const [selected, setSelected] = useState<string[]>([]);
  const layouts = actions.map(action => ({ action, spaces: startingPlacement(action) }));
  const coordinates = layouts[0]?.spaces.map(space => space.coordinate) ?? [];
  const matches = (choices: string[]) => layouts.filter(layout => choices.every((type, index) => layout.spaces[index]?.type === type));
  const candidates = matches(selected);
  const complete = candidates.length === 1 ? candidates[0] : undefined;
  if (!coordinates.length) return <p role="alert">Starting placements could not be displayed. Refresh the table to try again.</p>;
  return <section className="starting-placement" aria-label="Starting organism">
    <p>Choose the elements for your highlighted starting spaces. Only arrangements offered by the game are shown.</p>
    <div className="placement-choices">{coordinates.map(([ring, index], position) => {
      const possible = matches(selected.slice(0, position));
      const types = [...new Set(possible.map(layout => layout.spaces[position]?.type).filter(Boolean))];
      const value = selected[position] ?? (complete?.spaces[position]?.type ?? "");
      return <label key={`${ring}:${index}`}>Element at {ring} {index}
        <select value={value} disabled={disabled || (position > selected.length && !complete)} onChange={event => {
          const choices = [...selected.slice(0, position), event.target.value];
          setSelected(choices);
          const options = matches(choices);
          onPreview(options.length === 1 ? options[0].spaces : choices.map((type, i) => ({ coordinate: coordinates[i], type })));
        }}><option value="" disabled>Choose element</option>{types.map(type => <option key={type} value={type}>{type}</option>)}</select>
        {value && <svg className="placement-glyph" viewBox="-30 -30 60 60" aria-label={value}><ElementGlyph type={value} food={0} radius={24} color="#c7f59b" /></svg>}
      </label>;
    })}</div>
    <button className="primary" disabled={disabled || !complete} onClick={() => complete && onAction([complete.action])}>Place starting organism</button>
  </section>;
}
