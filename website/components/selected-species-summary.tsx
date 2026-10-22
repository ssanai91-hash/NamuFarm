"use client";

import { X } from "lucide-react";
import type { QuoteLine } from "@/lib/quote-form";

type Props = {
  lines: Pick<QuoteLine, "id" | "species">[];
  onRemove: (id: number) => void;
  locked?: boolean;
};

export default function SelectedSpeciesSummary({ lines, onRemove, locked = false }: Props) {
  const selected = lines.filter(line => line.species.trim());

  return <section className="selected-species-summary" aria-label="담은 묘목">
    <h3>담은 묘목 <span>· {selected.length}종</span></h3>
    {selected.length ? <ul className="selected-species-tags">
      {selected.map(line => <li key={line.id}>
        <span>{line.species.trim()}</span>
        <button type="button" disabled={locked} onClick={() => onRemove(line.id)} aria-label={`${line.species.trim()} 담기 해제`}><X aria-hidden="true" /></button>
      </li>)}
    </ul> : <p>묘목을 누르면 여기에 담겨요.</p>}
  </section>;
}
