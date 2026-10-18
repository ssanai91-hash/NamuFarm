"use client";

import { useEffect, useState } from "react";
import { Leaf, Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { catalogInitials, filterSpecies, type CatalogInitial } from "@/lib/species-catalog";
import { speciesIllustration } from "@/lib/species-illustrations";

type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onChoose: (name: string) => void;
};

const initials: Array<CatalogInitial | "all"> = [...catalogInitials, "all"];

function SpeciesPickerImage({ id }: { id: string }) {
  const [failed, setFailed] = useState(false);
  const illustration = speciesIllustration(id);
  return (
    <span className="species-picker-art" aria-hidden="true">
      {illustration && !failed
        ? <img src={illustration.src} width={256} height={256} loading="lazy" decoding="async" alt="" onError={() => setFailed(true)} />
        : <span className="species-picker-art-empty"><Leaf strokeWidth={1.3} /><span>그림 미등록</span></span>}
    </span>
  );
}

export default function SpeciesPickerDialog({ open, onOpenChange, onChoose }: Props) {
  const [initial, setInitial] = useState<CatalogInitial | "all">("ㄱ");
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (open) {
      setInitial("ㄱ");
      setQuery("");
    }
  }, [open]);

  const matches = filterSpecies(initial, query);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="species-picker-dialog" showCloseButton={false}>
        <DialogClose asChild>
          <Button type="button" variant="ghost" className="dialog-dismiss" aria-label="묘목 선택 창 닫기">
            <X aria-hidden="true" />
          </Button>
        </DialogClose>
        <DialogHeader>
          <DialogTitle>묘목 선택</DialogTitle>
          <DialogDescription>초성을 누른 다음 묘목 이름을 선택하세요.</DialogDescription>
        </DialogHeader>

        <div className="species-picker-initials" role="group" aria-label="첫 글자 선택">
          {initials.map(value => (
            <Button
              key={value}
              type="button"
              variant={initial === value ? "default" : "outline"}
              className="species-picker-initial"
              aria-pressed={initial === value}
              onClick={() => { setInitial(value); setQuery(""); }}
            >
              {value === "all" ? "전체" : value}
            </Button>
          ))}
        </div>

        <div className="species-picker-results-heading">
          <strong>{query ? "검색 결과" : initial === "all" ? "전체 묘목" : initial + "으로 시작하는 묘목"}</strong>
          <span>{matches.length}종</span>
        </div>
        <div className="species-picker-choices" role="group" aria-label="묘목 목록">
          {matches.map(species => (
            <Button
              type="button"
              key={species.id}
              variant="outline"
              className="species-picker-choice"
              onClick={() => onChoose(species.name)}
            >
              <SpeciesPickerImage id={species.id} />
              <span className="species-picker-choice-name">{species.name}</span>
            </Button>
          ))}
          {matches.length === 0 && <p className="species-picker-empty">찾는 묘목이 없어요. 이름을 직접 입력할 수 있어요.</p>}
        </div>

        <div className="species-picker-search">
          <Search aria-hidden="true" />
          <label className="sr-only" htmlFor="species-picker-search">묘목 이름 검색</label>
          <Input
            id="species-picker-search"
            type="search"
            autoComplete="off"
            maxLength={80}
            placeholder="묘목 이름이나 초성 검색 (선택)"
            value={query}
            onChange={event => { setQuery(event.target.value); if (event.target.value.trim()) setInitial("all"); }}
          />
        </div>
        <p className="species-picker-help">목록에 없는 묘목은 창을 닫고 직접 입력하세요.</p>
      </DialogContent>
    </Dialog>
  );
}
