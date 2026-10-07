"use client";

import { useState, type CSSProperties } from "react";
import { Check, Leaf, Plus, Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { artworkSlots, catalogInitials, filterSpecies, speciesCatalog, type CatalogInitial } from "@/lib/species-catalog";

type Props = { selectedNames: string[]; onSelect: (name: string) => void };
const pageSize = 24;

export default function SpeciesBrowser({ selectedNames, onSelect }: Props) {
  const [initial, setInitial] = useState<CatalogInitial | "all">("ㄱ");
  const [query, setQuery] = useState("");
  const [visibleCount, setVisibleCount] = useState(pageSize);
  const matches = filterSpecies(initial, query);
  const selected = new Set(selectedNames);

  function chooseInitial(value: CatalogInitial | "all") {
    setInitial(value); setQuery(""); setVisibleCount(pageSize);
  }

  return <section id="trees" className="trees-section wrap catalog-section" aria-labelledby="trees-title">
    <div className="section-heading catalog-heading">
      <div><span className="overline">FIND YOUR TREE</span><h2 id="trees-title">어떤 나무를 찾으세요?</h2><p>초성으로 찾아보고, 마음에 드는 묘목을 담아보세요.</p></div>
      <span className="catalog-total"><Leaf aria-hidden="true" /><strong>{speciesCatalog.length}</strong>가지 묘목</span>
    </div>
    <div className="catalog-controls">
      <div className="catalog-search"><Search aria-hidden="true" /><label htmlFor="catalog-search" className="sr-only">묘목 이름 또는 초성 검색</label><Input id="catalog-search" type="search" autoComplete="off" maxLength={80} placeholder="묘목 이름 또는 초성 · 예: 수국, ㅅㄱ" value={query} onChange={event => { setQuery(event.target.value); setInitial(event.target.value.trim() ? "all" : "ㄱ"); setVisibleCount(pageSize); }} />{query && <Button type="button" variant="ghost" size="icon" aria-label="묘목 검색 지우기" onClick={() => chooseInitial("ㄱ")}><X /></Button>}</div>
      <div className="initial-filter" role="group" aria-label="묘목 초성 선택">
        {(["all", ...catalogInitials] as const).map(value => <Button type="button" variant="ghost" key={value} className="initial-button" aria-pressed={initial === value} onClick={() => chooseInitial(value)}>{value === "all" ? "전체" : value}</Button>)}
      </div>
    </div>
    <div className="catalog-results-heading"><p role="status" aria-live="polite">{query.trim() ? `‘${query.trim()}’ 검색 결과` : initial === "all" ? "전체 묘목" : `${initial}으로 시작하는 묘목`} <strong>{matches.length}종</strong></p><span>선택하면 견적 요청에 담겨요</span></div>
    <div className="catalog-grid">{matches.slice(0, visibleCount).map(species => {
      const isSelected = selected.has(species.name);
      const slot = artworkSlots[species.artwork];
      const style = { "--art-x": `${(slot % 4) * 100 / 3}%`, "--art-y": `${Math.floor(slot / 4) * 50}%` } as CSSProperties;
      return <button type="button" key={species.id} data-species-id={species.id} className={`catalog-card art-${species.artwork}${isSelected ? " selected" : ""}`} onClick={() => onSelect(species.name)} aria-label={`${species.name}${isSelected ? ", 견적에 담긴 품목 확인" : " 견적 품목에 담기"}`} aria-pressed={isSelected}>
        <span className="catalog-card-top"><span>{species.initial}</span><span className="catalog-card-action">{isSelected ? <Check /> : <Plus />}</span></span>
        <span className="catalog-art" style={style} aria-hidden="true" />
        <span className="catalog-card-name">{species.name}</span>
        <span className="catalog-card-caption">{isSelected ? "견적에 담았어요" : "견적에 담기"}</span>
      </button>;
    })}</div>
    {matches.length === 0 && <div className="catalog-empty"><Leaf aria-hidden="true" /><h3>찾으시는 묘목이 없나요?</h3><p>다른 이름으로 검색하거나 견적 요청란에 직접 적어주세요.</p><Button asChild variant="outline"><a href="#quote">묘목 이름 직접 적기</a></Button></div>}
    {matches.length > visibleCount && <div className="catalog-more"><Button type="button" variant="outline" onClick={() => setVisibleCount(count => count + pageSize)}>묘목 더 보기 <span>{Math.min(visibleCount, matches.length)} / {matches.length}</span></Button></div>}
    <p className="section-note catalog-note"><Leaf aria-hidden="true" />그림은 묘목의 특징을 표현한 일러스트입니다. 실제 재고와 규격은 상담 시 확인해요.</p>
  </section>;
}
