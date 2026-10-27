"use client";

import type { RefObject } from "react";
import { Check, ExternalLink, Plus, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { plantingReviewedAt, type PlantingGuide } from "@/lib/planting-guide";

type Props = {
  guide: PlantingGuide | null;
  month: number;
  added: boolean;
  locked: boolean;
  canAdd: boolean;
  opener: RefObject<HTMLButtonElement | null>;
  onClose: () => void;
  onAdd: (name: string) => void;
};

export default function PlantingGuideDialog({ guide, month, added, locked, canAdd, opener, onClose, onAdd }: Props) {
  const preparing = !!guide && !guide.plantingMonths.includes(month);
  return <Dialog open={guide !== null} onOpenChange={open => { if (!open) onClose(); }}>
    <DialogContent placement="responsive-sheet" className="planting-guide-dialog" showCloseButton={false} onCloseAutoFocus={event => { event.preventDefault(); opener.current?.focus({ preventScroll: true }); }}>
      <DialogClose asChild><Button type="button" variant="ghost" className="dialog-dismiss" aria-label="묘목 안내 닫기"><X aria-hidden="true" /><span>닫기</span></Button></DialogClose>
      <DialogHeader><DialogTitle>{guide?.name} 알아보기</DialogTitle><DialogDescription>{month}월 안내 · {preparing ? "다음 식재 준비" : "식재 조건 확인"}</DialogDescription></DialogHeader>
      {guide && <><div className="planting-guide-body">
        <section><h3>심는 시기</h3><p>{guide.timing}</p></section>
        <section><h3>{preparing ? "미리 살펴보는 이유" : "이 시기에 살펴보는 이유"}</h3><p>{preparing ? guide.preparation : guide.reason}</p></section>
        <section><h3>심기 전 확인</h3><p>{guide.caution}</p></section>
        <details className="planting-guide-sources"><summary>안내 근거 보기</summary><ul>{guide.sources.map(source => <li key={source.url}><a href={source.url} target="_blank" rel="noopener noreferrer">{source.label}<ExternalLink aria-hidden="true" /><span className="sr-only"> · 새 창</span></a></li>)}</ul><p>검토일 {plantingReviewedAt} · 월별 목록은 일반적인 탐색 안내이며, 실제 식재는 지역·품종·묘목 상태에 따라 확인합니다.</p></details>
      </div><div className="planting-guide-footer">
        {!canAdd && !added && <p>한 번에 20종까지 담을 수 있어요. 담은 목록을 먼저 확인해주세요.</p>}
        <Button type="button" disabled={locked || added || !canAdd} onClick={() => { if (locked || added || !canAdd) return; onAdd(guide.name); onClose(); }}>{added ? <Check aria-hidden="true" /> : <Plus aria-hidden="true" />}{added ? "이미 담은 묘목" : locked ? "접수 확인 후 담을 수 있어요" : "견적에 담기"}</Button>
        <p>심을 지역을 알려주시면 식재 시기를 함께 상담해드려요.</p>
      </div></>}
    </DialogContent>
  </Dialog>;
}
