"use client";

import { Check } from "lucide-react";

type Props = {
  id: string;
  name: string;
  selected: boolean;
  disabled?: boolean;
  onSelect: () => void;
};

export default function SpeciesChoiceCard({ id, name, selected, disabled, onSelect }: Props) {
  return <button type="button" data-species-id={id} className={`catalog-card${selected ? " selected" : ""}`} disabled={disabled} onClick={onSelect} aria-label={`${name} 선택`} aria-pressed={selected}>
    <span className="catalog-card-name">{name}</span>
    <span className="catalog-card-choice" aria-hidden="true">{selected && <Check />}</span>
  </button>;
}
