"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import { Apple, Check, ChevronRight, Fence, Flower2, Leaf, Search, Sprout, TreeDeciduous, Trees, X } from "lucide-react";
import SpeciesChoiceCard from "@/components/species-choice-card";
import PlantingGuideDialog from "@/components/planting-guide-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { catalogInitials, filterSpecies, type CatalogInitial } from "@/lib/species-catalog";
import { purposes, filterSpeciesByPurpose, type PurposeId } from "@/lib/species-purposes";
import { guidesForMonth, koreanMonth, monthGuidance, plantingGuides } from "@/lib/planting-guide";
import { isEmptyLine, type QuoteLine } from "@/lib/quote-form";

export type SpeciesPickerMode = "name" | "purpose" | "planting";
type Props = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onChoose: (name: string) => void;
  onAddMany: (names: string[]) => void;
  selectedName: string;
  initialMode: SpeciesPickerMode;
  lines: QuoteLine[];
  locked: boolean;
  opener: RefObject<HTMLButtonElement | null>;
};
const purposeIcons = { fruit: Apple, hedge: Fence, flower: Flower2, shade: TreeDeciduous, garden: Trees, lawn: Sprout };

export default function SpeciesPickerDialog({ open, onOpenChange, onChoose, onAddMany, selectedName, initialMode, lines, locked, opener }: Props) {
  const [mode, setMode] = useState<SpeciesPickerMode>(initialMode);
  const [initial, setInitial] = useState<CatalogInitial | "all">("ㄱ");
  const [query, setQuery] = useState("");
  const [purpose, setPurpose] = useState<PurposeId>("fruit");
  const [month, setMonth] = useState(koreanMonth);
  const [multiple, setMultiple] = useState(false);
  const [pending, setPending] = useState<string[]>([]);
  const [guideName, setGuideName] = useState<string | null>(null);
  const title = useRef<HTMLHeadingElement>(null);
  const guideOpener = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    if (open) {
      setMode(initialMode); setInitial("ㄱ"); setQuery(""); setPurpose("fruit");
      setMonth(koreanMonth()); setMultiple(false); setPending([]); setGuideName(null);
    }
  }, [open, initialMode]);

  const alreadyAdded = new Set(lines.map(line => line.species.trim()).filter(Boolean));
  const available = Math.max(0, 20 - lines.length + lines.filter(isEmptyLine).length);
  const canSelectMore = pending.length < available;
  const allSpecies = filterSpecies("all", "");
  const monthGuides = guidesForMonth(month);
  const matches = mode === "purpose" ? filterSpeciesByPurpose(allSpecies, purpose)
    : mode === "planting" ? allSpecies.filter(species => monthGuides.some(guide => guide.name === species.name))
    : filterSpecies(initial, query);
  const activeGuide = plantingGuides.find(guide => guide.name === guideName) ?? null;

  function choose(name: string) {
    if (locked) return;
    if (!multiple) {
      if (!alreadyAdded.has(name) || name === selectedName.trim()) onChoose(name);
      return;
    }
    if (alreadyAdded.has(name)) return;
    setPending(old => old.includes(name) ? old.filter(value => value !== name)
      : old.length < available ? [...old, name] : old);
  }
  const guideAdded = !!activeGuide && (multiple
    ? alreadyAdded.has(activeGuide.name) || pending.includes(activeGuide.name)
    : alreadyAdded.has(activeGuide.name) && activeGuide.name !== selectedName.trim());

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="species-picker-dialog integrated-species-picker" showCloseButton={false}
        onOpenAutoFocus={event => { event.preventDefault(); title.current?.focus({ preventScroll: true }); }}
        onCloseAutoFocus={event => { event.preventDefault(); opener.current?.focus({ preventScroll: true }); }}>
        <DialogClose asChild><Button type="button" variant="ghost" className="dialog-dismiss" aria-label="묘목 찾기 창 닫기"><X aria-hidden="true" /></Button></DialogClose>
        <DialogHeader><DialogTitle ref={title} tabIndex={-1}>묘목 찾기</DialogTitle>
          <DialogDescription>{multiple ? "여러 묘목을 고른 뒤 아래 담기 버튼을 눌러주세요." : "이름을 누르면 작성 중인 품목에 들어갑니다."}</DialogDescription>
        </DialogHeader>
        <div className="picker-scroll">
          <div className="picker-tabs" role="group" aria-label="묘목 찾는 방법">
            {([["name", "초성 ㄱㄴㄷ"], ["purpose", "용도별"], ["planting", "심는 시기"]] as const).map(([value, text]) =>
              <Button key={value} type="button" variant="outline" aria-pressed={mode === value} onClick={() => setMode(value)}>{text}</Button>)}
          </div>
          <div className="picker-selection-mode"><Button type="button" variant="outline" aria-pressed={multiple}
            onClick={() => { setMultiple(!multiple); setPending([]); }}>여러 종 고르기 <Check aria-hidden="true" /></Button>
            <span>{multiple ? `${pending.length}종 선택 · 추가 가능 ${available}종` : "한 종 바로 선택"}</span></div>
          {mode === "name" ? <>
            <div className="species-picker-initials" role="group" aria-label="첫 글자 선택">
              {catalogInitials.map(value => <Button key={value} type="button" variant={initial === value ? "default" : "outline"}
                className="species-picker-initial" aria-pressed={initial === value} onClick={() => { setInitial(value); setQuery(""); }}>{value}</Button>)}
            </div>
            <div className="species-picker-search"><Search aria-hidden="true" /><label className="sr-only" htmlFor="species-picker-search">묘목 이름 검색</label>
              <Input id="species-picker-search" type="search" autoComplete="off" maxLength={80} placeholder="묘목 이름이나 초성 검색"
                value={query} onChange={event => { setQuery(event.target.value); setInitial(event.target.value.trim() ? "all" : "ㄱ"); }} /></div>
          </> : mode === "purpose" ? <>
            <p className="purpose-prompt">어떤 용도로 찾으세요?</p>
            <div className="purpose-options" role="group" aria-label="나무 용도">{purposes.map(item => { const Icon = purposeIcons[item.id];
              return <button key={item.id} type="button" className="purpose-option" aria-pressed={purpose === item.id} onClick={() => setPurpose(item.id)}><Icon aria-hidden="true" /><span>{item.label}</span></button>;
            })}</div>
            <p className="field-help">용도는 찾기 위한 안내예요. 재배 환경과 재고는 상담 후 확인합니다.</p>
          </> : <>
            <div className="planting-month-grid" role="group" aria-label="심는 시기 안내 월 선택">{Array.from({ length: 12 }, (_, index) => index + 1).map(value =>
              <button key={value} type="button" aria-pressed={month === value} onClick={() => setMonth(value)}>{value}월</button>)}</div>
            <p className="field-help">{month}월 · {monthGuidance(month)}</p>
          </>}
          <div className="species-picker-results-heading"><strong>{mode === "name" ? query ? "검색 결과" : initial === "all" ? "전체 묘목" : initial + " 묘목"
            : mode === "purpose" ? purposes.find(item => item.id === purpose)?.title : "설명을 보고 담아보세요"}</strong><div className="species-picker-results-actions"><span>{matches.length}종</span>
            {mode === "name" && <Button type="button" variant="outline" aria-pressed={initial === "all" && !query} onClick={() => { setInitial("all"); setQuery(""); }}>전체</Button>}</div></div>
          <div className="catalog-grid species-picker-choices" role="group" aria-label="묘목 목록">{matches.map(species => {
            const selected = multiple ? alreadyAdded.has(species.name) || pending.includes(species.name) : selectedName.trim() === species.name;
            const unavailable = multiple ? alreadyAdded.has(species.name) || (!pending.includes(species.name) && !canSelectMore)
              : alreadyAdded.has(species.name) && species.name !== selectedName.trim();
            return mode === "planting" ? <button key={species.id} type="button" className={`catalog-card planting-choice${selected ? " selected" : ""}`} aria-haspopup="dialog"
              aria-label={`${species.name} 심는 시기 알아보기${alreadyAdded.has(species.name) ? " · 이미 담김" : ""}`}
              onClick={event => { guideOpener.current = event.currentTarget; setGuideName(species.name); }}>
              <span className="catalog-card-name">{species.name}{!monthGuides.find(guide => guide.name === species.name)?.plantingMonths.includes(month) && <small>다음 식재 준비</small>}</span>
              <span className="planting-choice-icon" aria-hidden="true">{selected ? <Check /> : <ChevronRight />}</span>
            </button> : <SpeciesChoiceCard key={species.id} id={species.id} name={species.name} selected={selected} disabled={locked || unavailable} onSelect={() => choose(species.name)} />;
          })}</div>
          {!matches.length && (mode === "planting" ? <div className="catalog-empty planting-empty"><Leaf aria-hidden="true" /><h3>이번 달은 심기 전 준비부터</h3><p>다른 달의 묘목을 미리 살펴보세요.</p>
            <div><Button type="button" variant="outline" onClick={() => setMonth(3)}>3월 미리보기</Button><Button type="button" variant="outline" onClick={() => setMonth(10)}>10월 미리보기</Button></div></div>
            : <p className="species-picker-empty">찾는 묘목이 없어요. 창을 닫고 이름을 직접 입력할 수 있어요.</p>)}
        </div>
        <div className="picker-footer">
          {multiple && <><p role="status" aria-live="polite">{pending.length ? pending.join(" · ") : available ? "담을 묘목을 골라주세요." : "한 번에 20종까지 담을 수 있어요. 작성 중인 품목을 먼저 확인해주세요."}</p>
            <Button type="button" disabled={locked || !pending.length} onClick={() => { if (!locked) onAddMany(pending); }}>{pending.length}종 담기</Button></>}
          <p className="species-picker-help">목록에 없는 묘목은 창을 닫고 직접 입력하세요.</p>
        </div>
        <PlantingGuideDialog guide={activeGuide} month={month} added={guideAdded} locked={locked}
          canAdd={!multiple || canSelectMore} opener={guideOpener} onClose={() => setGuideName(null)} onAdd={choose} />
      </DialogContent>
    </Dialog>
  );
}
