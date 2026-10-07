"use client";
import { useState } from "react";
import { Leaf, Search, X, ShoppingBasket } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { catalogInitials, filterSpecies, type CatalogInitial } from "@/lib/species-catalog";
import { speciesIllustration } from "@/lib/species-illustrations";
import type { QuoteLine } from "@/lib/quote-form";
type Props = { lines: QuoteLine[]; onSelect: (name: string) => void; onRemove: (id: number) => void; notice: string; locked: boolean };
const pageSize = 24;
function PlantImage({ id, eager }: { id: string; eager: boolean }) {
  const [failed, setFailed] = useState(false);
  const illustration = speciesIllustration(id);
  return <span className="catalog-art" aria-hidden="true">{illustration && !failed
    ? <img src={illustration.src} width={256} height={256} loading={eager ? "eager" : "lazy"} decoding="async" alt="" onError={() => setFailed(true)} />
    : <span className="art-pending"><Leaf strokeWidth={1.3} /><span>그림 준비 중</span></span>}</span>;
}
export default function SpeciesBrowser({ lines, onSelect, onRemove, notice, locked }: Props) {
  const [initial, setInitial] = useState<CatalogInitial | "all">("ㄱ");
  const [query, setQuery] = useState("");
  const [visibleCount, setVisibleCount] = useState(pageSize);
  const matches = filterSpecies(initial, query);
  const selectedItems = lines.filter(line => line.species.trim());
  const selected = new Set(selectedItems.map(line => line.species.trim()));
  function chooseInitial(value: CatalogInitial | "all") { setInitial(value); setQuery(""); setVisibleCount(pageSize); }
  return <section id="trees" className="trees-section wrap catalog-section" aria-labelledby="trees-title">
    <div className="section-heading catalog-heading"><div><span className="overline">나무팜 묘목 안내</span><h2 id="trees-title">어떤 나무를 찾으세요?</h2></div><p className="catalog-direct-note">찾는 묘목이 없다면, 아래 견적 요청란에 묘목 이름을 직접 적어주세요.</p></div>
    <div className="catalog-layout"><div className="catalog-main">
      <div className="catalog-controls"><label className="search-label" htmlFor="catalog-search">묘목 이름으로 찾기</label>
        <div className="catalog-search"><Search aria-hidden="true" /><Input id="catalog-search" type="search" autoComplete="off" maxLength={80} placeholder="예: 수국 또는 ㅅㄱ" aria-describedby="search-help" value={query} onChange={event => { setQuery(event.target.value); setInitial(event.target.value.trim() ? "all" : "ㄱ"); setVisibleCount(pageSize); }} />{query && <Button type="button" variant="ghost" size="icon" aria-label="묘목 검색 지우기" onClick={() => chooseInitial("ㄱ")}><X /></Button>}</div>
        <p id="search-help" className="field-help">이름이나 초성으로 검색해요.</p>
        <div className="initial-filter" role="group" aria-label="첫 글자로 묘목 찾기">{(["all", ...catalogInitials] as const).map(value => <Button type="button" variant="ghost" key={value} className="initial-button" aria-pressed={initial === value} onClick={() => chooseInitial(value)}>{value === "all" ? "전체" : value}</Button>)}</div>
      </div>
      <p className="catalog-note">그림은 종류를 알아보기 위한 참고용입니다.<br />실제 묘목의 상태는 견적 후 상담을 통해 가능합니다.</p>
      <p className="catalog-results-heading">여러 품목을 함께 담으세요</p>
      <p className="catalog-live" role="status" aria-live="polite" aria-atomic="true">{notice || "카드를 누르면 견적에 담겨요."}</p>
      <div className="catalog-grid">{matches.slice(0, visibleCount).map((species, index) => {
        const isSelected = selected.has(species.name);
        return <button type="button" key={species.id} data-species-id={species.id} className={`catalog-card${isSelected ? " selected" : ""}`} disabled={locked} onClick={() => onSelect(species.name)} aria-label={`${species.name}, ${isSelected ? "견적에 담았어요" : "견적에 담기"}`} aria-pressed={isSelected}>
          <PlantImage id={species.id} eager={index < 6} /><span className="catalog-card-name">{species.name}</span><span className="catalog-card-caption">{isSelected ? "견적에 담았어요" : "견적에 담기"}</span>
        </button>;
      })}</div>
      {matches.length === 0 && <div className="catalog-empty"><Leaf aria-hidden="true" /><h3>검색 결과가 없어요.</h3><p>다른 이름으로 찾거나 묘목 이름을 직접 적어주세요.</p><Button asChild variant="outline"><a href="#quote">묘목 이름 직접 적기</a></Button></div>}
      {matches.length > visibleCount && <div className="catalog-more"><Button type="button" variant="outline" onClick={() => setVisibleCount(count => count + pageSize)}>묘목 더 보기 <span>{Math.min(visibleCount, matches.length)} / {matches.length}</span></Button></div>}
    </div><aside className="catalog-basket" aria-labelledby="basket-title"><div className="basket-title"><ShoppingBasket aria-hidden="true" /><h3 id="basket-title">담은 묘목 <span>{selectedItems.length}종</span></h3></div>
      {selectedItems.length ? <ul>{selectedItems.map(line => <li key={line.id}><span><strong>{line.species}</strong><small>{line.quantity ? `${Number(line.quantity).toLocaleString()}주` : "수량을 입력해주세요"}</small></span><Button variant="ghost" type="button" disabled={locked} onClick={() => onRemove(line.id)} aria-label={`${line.species} 빼기`}>빼기</Button></li>)}</ul> : <p className="basket-empty">필요한 묘목을 담아보세요.<br />수량은 다음에 입력해요.</p>}
      <Button asChild className="basket-next"><a href="#quote">{selectedItems.length ? "수량 입력하기" : "묘목 이름 직접 적기"}</a></Button><p className="field-help">견적을 요청해도 주문이나 결제가 확정되지 않아요.</p>
    </aside></div>
  </section>;
}
