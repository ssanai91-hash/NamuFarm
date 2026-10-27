"use client";
import { useRef, useState } from "react";
import { Apple, Check, ChevronDown, ChevronLeft, ChevronRight, Fence, Flower2, Leaf, Search, ShoppingBasket, Sprout, TreeDeciduous, Trees, X } from "lucide-react";
import SpeciesChoiceCard from "@/components/species-choice-card";
import SelectedSpeciesSummary from "@/components/selected-species-summary";
import PlantingGuideDialog from "@/components/planting-guide-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { catalogInitials, filterSpecies, type CatalogInitial } from "@/lib/species-catalog";
import { filterSpeciesByPurpose, purposes, type PurposeId } from "@/lib/species-purposes";
import { isEmptyLine, type QuoteLine } from "@/lib/quote-form";
import { guidesForMonth, koreanMonth, monthGuidance, plantingGuides } from "@/lib/planting-guide";
type Props = { lines: QuoteLine[]; onSelect: (name: string) => void; onRemove: (id: number) => void; notice: string; locked: boolean };
const pageSize = 10;
const purposeIcons = { fruit: Apple, hedge: Fence, flower: Flower2, shade: TreeDeciduous, garden: Trees, lawn: Sprout };
export default function SpeciesBrowser({ lines, onSelect, onRemove, notice, locked }: Props) {
  const [mode, setMode] = useState<"purpose" | "name" | "planting">("purpose");
  const [purpose, setPurpose] = useState<PurposeId>("fruit");
  const [initial, setInitial] = useState<CatalogInitial | "all">("ㄱ");
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [month, setMonth] = useState(koreanMonth);
  const [monthPickerOpen, setMonthPickerOpen] = useState(false);
  const selectedMonthRef = useRef<HTMLButtonElement | null>(null);
  const [guideName, setGuideName] = useState<string | null>(null);
  const guideOpener = useRef<HTMLButtonElement | null>(null);
  const monthGuides = guidesForMonth(month);
  const allSpecies = filterSpecies("all", "");
  const matches = mode === "planting" ? monthGuides.flatMap(guide => {
    const species = allSpecies.find(item => item.name === guide.name);
    return species ? [species] : [];
  }) : mode === "purpose" ? filterSpeciesByPurpose(allSpecies, purpose) : filterSpecies(initial, query);
  const pageCount = Math.max(1, Math.ceil(matches.length / pageSize));
  const currentPage = Math.min(page, pageCount - 1);
  const pageMatches = matches.slice(currentPage * pageSize, (currentPage + 1) * pageSize);
  const activePurpose = purposes.find(item => item.id === purpose)!;
  const selectedItems = lines.filter(line => line.species.trim());
  const selected = new Set(selectedItems.map(line => line.species.trim()));
  const activeGuide = plantingGuides.find(guide => guide.name === guideName) ?? null;
  const canAdd = lines.length < 20 || lines.some(isEmptyLine);
  function chooseInitial(value: CatalogInitial | "all") { setInitial(value); setQuery(""); setPage(0); }
  function choosePurpose(value: PurposeId) { setPurpose(value); setPage(0); }
  function changePage(nextPage: number) {
    // Pagination changes only the list; keep the viewport and pager focus in place.
    setPage(Math.max(0, Math.min(nextPage, pageCount - 1)));
  }
  return <section id="trees" className="trees-section wrap catalog-section" aria-labelledby="trees-title">
    <div className="section-heading catalog-heading"><div><span className="overline">나무팜 묘목 안내</span><h2 id="trees-title">어떤 나무를 찾으세요?</h2></div><p className="catalog-direct-note">찾는 묘목이 없다면, 아래 견적 요청란에 묘목 이름을 직접 적어주세요.</p></div>
    <div className="catalog-layout"><div className="catalog-main">
      <div className="catalog-mode-tabs" role="group" aria-label="묘목 찾는 방법">
        <button type="button" aria-pressed={mode === "name"} onClick={() => { setMode("name"); setPage(0); }}>이름·초성</button>
        <button type="button" aria-pressed={mode === "purpose"} onClick={() => { setMode("purpose"); setPage(0); }}>용도</button>
        <button type="button" aria-pressed={mode === "planting"} onClick={() => { setMode("planting"); setPage(0); }}>심는 시기</button>
      </div>
      {mode === "purpose" ? <div className="catalog-purpose-controls">
        <p className="purpose-prompt">어떤 용도로 찾으세요?</p>
        <div className="purpose-options" role="group" aria-label="나무 용도">{purposes.map(item => { const Icon = purposeIcons[item.id]; return <button key={item.id} type="button" className="purpose-option" aria-pressed={purpose === item.id} onClick={() => choosePurpose(item.id)}><Icon aria-hidden="true" /><span>{item.label}</span></button>; })}</div>
      </div> : mode === "planting" ? <div className="planting-controls">
        <div className="planting-heading"><h3>지금 심을 나무가 궁금하세요?</h3><Popover open={monthPickerOpen} onOpenChange={setMonthPickerOpen}>
          <PopoverTrigger asChild><button type="button" className="planting-month" aria-label={`심는 시기 안내 월 선택, 현재 ${month}월`} aria-describedby="planting-month-help">{month}월<ChevronDown aria-hidden="true" /></button></PopoverTrigger>
          <PopoverContent className="planting-month-picker" align="end" sideOffset={8} collisionPadding={12} aria-label="심는 시기 안내 월 선택" onOpenAutoFocus={event => { event.preventDefault(); selectedMonthRef.current?.focus({ preventScroll: true }); }}>
            <p>월 선택</p><div className="planting-month-grid" role="group" aria-label="1월부터 12월">{Array.from({ length: 12 }, (_, index) => index + 1).map(value => <button key={value} ref={value === month ? selectedMonthRef : undefined} type="button" aria-pressed={month === value} onClick={() => { setMonth(value); setPage(0); setMonthPickerOpen(false); }}>{value}월</button>)}</div>
          </PopoverContent>
        </Popover></div>
        <p id="planting-month-help">{monthGuidance(month)}</p>
      </div> : <div className="catalog-controls"><label className="search-label" htmlFor="catalog-search">묘목 이름으로 찾기</label>
        <div className="catalog-search"><Search aria-hidden="true" /><Input id="catalog-search" type="search" autoComplete="off" maxLength={80} placeholder="예: 수국 또는 ㅅㄱ" aria-describedby="search-help" value={query} onChange={event => { setQuery(event.target.value); setInitial(event.target.value.trim() ? "all" : "ㄱ"); setPage(0); }} />{query && <Button type="button" variant="ghost" size="icon" aria-label="묘목 검색 지우기" onClick={() => chooseInitial("ㄱ")}><X /></Button>}</div>
        <p id="search-help" className="field-help">이름이나 초성으로 검색해요.</p>
        <div className="initial-filter" role="group" aria-label="첫 글자로 묘목 찾기">{catalogInitials.map(value => <Button type="button" variant="ghost" key={value} className="initial-button" aria-pressed={initial === value} onClick={() => chooseInitial(value)}>{value}</Button>)}</div>
      </div>}
      {lines.some(line => line.species.trim()) && <SelectedSpeciesSummary lines={lines} onRemove={onRemove} locked={locked} />}
      <div className="catalog-result-intro">{mode === "purpose" && <h3>{activePurpose.title}</h3>}<p className="catalog-results-heading">{mode === "planting" ? matches.length > 0 ? "묘목을 누르면 심는 시기를 알아볼 수 있어요." : "다른 달의 묘목을 미리 알아보세요." : "눌러서 담기 · 다시 눌러 해제"}</p></div>
      <p className={notice.startsWith("한 번에 20종") || notice.startsWith("20종까지") ? "catalog-live" : "sr-only"} role="status" aria-live="polite" aria-atomic="true">{notice}</p>
      <div className="catalog-grid" id="catalog-species-list">{pageMatches.map(species => mode === "planting" ? <button key={species.id} type="button" className={`catalog-card planting-choice${selected.has(species.name) ? " selected" : ""}`} aria-haspopup="dialog" aria-label={`${species.name} 심는 시기 알아보기${selected.has(species.name) ? " · 이미 담김" : ""}`} onClick={event => { guideOpener.current = event.currentTarget; setGuideName(species.name); }}>
        <span className="catalog-card-name">{species.name}{!monthGuides.find(guide => guide.name === species.name)?.plantingMonths.includes(month) && <small>다음 식재 준비</small>}</span><span className="planting-choice-icon" aria-hidden="true">{selected.has(species.name) ? <Check /> : <ChevronRight />}</span>
      </button> : <SpeciesChoiceCard key={species.id} id={species.id} name={species.name} selected={selected.has(species.name)} disabled={locked} onSelect={() => onSelect(species.name)} />)}</div>
      {matches.length === 0 && (mode === "planting" ? <div className="catalog-empty planting-empty"><Leaf aria-hidden="true" /><h3>이번 달은 심기 전 준비부터</h3><p>봄·가을 안내에서 마음에 드는 묘목을 미리 살펴보세요.</p><div><Button type="button" variant="outline" onClick={() => { setMonth(3); setPage(0); }}>3월 미리보기</Button><Button type="button" variant="outline" onClick={() => { setMonth(10); setPage(0); }}>10월 미리보기</Button></div></div> : <div className="catalog-empty"><Leaf aria-hidden="true" /><h3>검색 결과가 없어요.</h3><p>다른 이름으로 찾거나 묘목 이름을 직접 적어주세요.</p><Button asChild variant="outline"><a href="#quote">묘목 이름 직접 적기</a></Button></div>)}
      {matches.length > 0 && (mode !== "planting" || pageCount > 1) && <nav className="catalog-pagination" aria-label="묘목 목록 페이지">
        <Button type="button" variant="outline" disabled={currentPage === 0} aria-controls="catalog-species-list" onClick={() => changePage(currentPage - 1)}><ChevronLeft aria-hidden="true" />이전</Button>
        <span role="status" aria-live="polite" aria-atomic="true">{currentPage + 1} / {pageCount}<small>페이지</small></span>
        <Button type="button" variant="outline" disabled={currentPage === pageCount - 1} aria-controls="catalog-species-list" onClick={() => changePage(currentPage + 1)}>다음<ChevronRight aria-hidden="true" /></Button>
      </nav>}
      {mode === "purpose" && <p className="purpose-note">용도는 묘목을 찾기 위한 안내예요. 재배 환경과 재고는 견적 요청 후 확인해 주세요.</p>}
    </div><aside className="catalog-basket" aria-labelledby="basket-title"><div className="basket-title"><ShoppingBasket aria-hidden="true" /><h3 id="basket-title">담은 묘목 <span>{selectedItems.length}종</span></h3></div>
      {selectedItems.length ? <ul>{selectedItems.map(line => <li key={line.id}><span><strong>{line.species}</strong><small>{line.quantity ? `${Number(line.quantity).toLocaleString()}주` : "수량을 입력해주세요"}</small></span><Button variant="ghost" type="button" disabled={locked} onClick={() => onRemove(line.id)} aria-label={`${line.species} 빼기`}>빼기</Button></li>)}</ul> : <p className="basket-empty">필요한 묘목을 담아보세요.<br />수량은 다음에 입력해요.</p>}
      <Button asChild className="basket-next"><a href="#quote">{selectedItems.length ? "수량 입력하기" : "묘목 이름 직접 적기"}</a></Button><p className="field-help">견적을 요청해도 주문이나 결제가 확정되지 않아요.</p>
    </aside></div>
    <PlantingGuideDialog guide={activeGuide} month={month} added={!!activeGuide && selected.has(activeGuide.name)} locked={locked} canAdd={canAdd} opener={guideOpener} onClose={() => setGuideName(null)} onAdd={onSelect} />
  </section>;
}
