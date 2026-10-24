"use client";

import { useEffect, useRef, useState, type FormEvent, type RefObject } from "react";
import { CheckCheck, ChevronRight, CircleCheck, LoaderCircle, LockKeyhole, Phone, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { businessInfo } from "@/lib/business-info";
import { inquiryCategories, inquiryPrivacyNotice, inquirySchema, validatedInquiryReceipt, type InquiryReceipt, type InquiryRequest } from "@/lib/inquiry";
import { newRequestId } from "@/lib/quote-schema";

const pendingKey = "namufarm.pending-inquiry";
type Fields = { category: InquiryRequest["category"]; customerName: string; customerPhone: string; customerEmail: string; message: string; consent: boolean; website: string };
const emptyFields: Fields = { category: "묘목·재배", customerName: "", customerPhone: "", customerEmail: "", message: "", consent: false, website: "" };
export default function CustomerInquiryDialog({ open, onOpenChange, opener }: { open: boolean; onOpenChange: (value: boolean) => void; opener: RefObject<HTMLButtonElement | null> }) {
  const [fields, setFields] = useState<Fields>(emptyFields);
  const [pending, setPending] = useState<InquiryRequest | null>(null);
  const [busy, setBusy] = useState(false);
  const [ready, setReady] = useState<boolean | null>(null);
  const [checking, setChecking] = useState(false);
  const [error, setError] = useState("");
  const [problems, setProblems] = useState<Record<string, string>>({});
  const [receipt, setReceipt] = useState<InquiryReceipt | null>(null);
  const [conflict, setConflict] = useState(false);
  const inFlight = useRef(false);
  const title = useRef<HTMLHeadingElement>(null);
  const errorRef = useRef<HTMLDivElement>(null);
  const locked = busy || !!pending;
  function changeOpen(value: boolean) { onOpenChange(value); if (!value && receipt) setReceipt(null); }
  useEffect(() => {
    try {
      const saved = inquirySchema.safeParse(JSON.parse(sessionStorage.getItem(pendingKey) || "null"));
      if (saved.success) { setPending(saved.data); setFields(saved.data); }
    } catch { /* No automatic submission or deletion of another form's pending record. */ }
  }, []);
  async function checkReady(signal?: AbortSignal) {
    setChecking(true);
    try {
      const response = await fetch("/api/inquiries", { cache: "no-store", signal });
      const info = response.ok ? await response.json() as { ready?: boolean } : null;
      setReady(info?.ready === true);
    } catch { if (!signal?.aborted) setReady(false); }
    finally { if (!signal?.aborted) setChecking(false); }
  }
  useEffect(() => {
    if (!open) return;
    const abort = new AbortController();
    void checkReady(abort.signal);
    return () => abort.abort();
  }, [open]);
  function update<K extends keyof Fields>(key: K, value: Fields[K]) {
    setFields(previous => ({ ...previous, [key]: value }));
    setProblems(previous => { const next = { ...previous }; delete next[key]; return next; });
  }
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (inFlight.current || conflict) return;
    if (!pending && (ready !== true || checking)) { if (!checking) void checkReady(); return; }
    const hadPending = !!pending;
    const parsed = inquirySchema.safeParse(pending ?? { ...fields, requestId: newRequestId() });
    if (!parsed.success) {
      const errors: Record<string, string> = {};
      parsed.error.issues.forEach(issue => { const field = String(issue.path[0]); if (!errors[field]) errors[field] = issue.message; });
      setProblems(errors); setError("아래 입력 내용을 확인해주세요.");
      requestAnimationFrame(() => document.getElementById(`inquiry-${Object.keys(errors)[0]}`)?.focus());
      return;
    }
    const payload = parsed.data;
    try { sessionStorage.setItem(pendingKey, JSON.stringify(payload)); }
    catch { setError("접수 확인 정보를 임시 보관할 수 없어요. 일반 브라우저에서 다시 열거나 전화로 상담해주세요."); return; }
    inFlight.current = true; setBusy(true); setPending(payload); setError(""); setProblems({});
    try {
      const response = await fetch("/api/inquiries", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(payload), signal: AbortSignal.timeout(90000) });
      const result = await response.json() as { receiptState?: string; error?: string };
      const confirmed = validatedInquiryReceipt(result, payload.requestId);
      setConflict(response.status === 409);
      if (response.ok && confirmed) {
        // A confirmed receipt remains success even when browser cleanup is unavailable.
        try { sessionStorage.removeItem(pendingKey); } catch { /* A later retry is deduplicated by its UUID. */ }
        setReceipt(confirmed); setPending(null); setFields(emptyFields);
        requestAnimationFrame(() => title.current?.focus({ preventScroll: true }));
      } else {
        if (!hadPending && result.receiptState === "not_sent") {
          try { sessionStorage.removeItem(pendingKey); setPending(null); } catch { /* Keep exact inputs if the token cannot be cleared. */ }
        }
        throw new Error(result.error || "접수 결과를 확인하지 못했어요. 잠시 후 같은 문의로 확인해주세요.");
      }
    } catch (e) {
      setError(e instanceof Error && !["TypeError", "SyntaxError", "TimeoutError"].includes(e.name) ? e.message : "접수 결과를 확인하지 못했어요. 입력 내용은 유지됩니다. 잠시 후 같은 문의로 접수를 확인해주세요.");
      requestAnimationFrame(() => errorRef.current?.focus({ preventScroll: true }));
    } finally { inFlight.current = false; setBusy(false); }
  }
  function fieldProps(key: keyof Fields) { return { id: `inquiry-${key}`, "aria-invalid": !!problems[key] || undefined, "aria-describedby": problems[key] ? `inquiry-${key}-error` : undefined }; }
  function problem(key: keyof Fields) { return problems[key] && <p className="inquiry-field-error" id={`inquiry-${key}-error`}>{problems[key]}</p>; }
  return <Dialog open={open} onOpenChange={changeOpen}>
    <DialogContent className="customer-inquiry-dialog" showCloseButton={false} onOpenAutoFocus={event => { event.preventDefault(); title.current?.focus({ preventScroll: true }); }} onCloseAutoFocus={event => { if (opener.current?.isConnected) { event.preventDefault(); opener.current.focus({ preventScroll: true }); } }}>
      <button type="button" className="inquiry-close" aria-label="문의 창 닫기" onClick={() => changeOpen(false)}><X aria-hidden="true" /></button>
      <DialogHeader><DialogTitle ref={title} tabIndex={-1}>{receipt ? "문의가 접수됐어요" : "문의 남기기"}</DialogTitle><DialogDescription className={receipt ? undefined : "sr-only"}>{receipt ? "판매자가 확인한 뒤 남겨주신 연락처로 답변드려요." : "문의 종류와 연락처, 궁금한 내용을 입력해주세요."}</DialogDescription></DialogHeader>
      <div className="inquiry-scroll">
        {receipt ? <div className="inquiry-success"><CircleCheck aria-hidden="true" /><p>문의 접수번호</p><strong>{receipt.inquiryNumber}</strong><p>질문 내용은 판매자만 확인할 수 있어요.</p></div> : <form id="customer-inquiry" onSubmit={submit} noValidate>
          {pending && <p className="inquiry-pending" role="status">접수 확인 중인 문의예요. 중복되지 않도록 같은 내용으로 확인합니다.</p>}
          {error && <div className="inquiry-error" role="alert" ref={errorRef} tabIndex={-1}>{error}</div>}
          <fieldset disabled={locked}><legend>문의 종류</legend><div className="inquiry-types">{inquiryCategories.map(category => <button key={category} type="button" aria-pressed={fields.category === category} onClick={() => update("category", category)}>{category}</button>)}</div></fieldset>
          <div className="inquiry-contact"><h3>답변받을 연락처</h3><div className="inquiry-contact-row"><div><label htmlFor="inquiry-customerName">이름 <span>필수</span></label><input {...fieldProps("customerName")} disabled={locked} autoComplete="name" maxLength={50} placeholder="이름" value={fields.customerName} onChange={event => update("customerName", event.target.value)} required />{problem("customerName")}</div><div><label htmlFor="inquiry-customerPhone">휴대폰 번호 <span>필수</span></label><input {...fieldProps("customerPhone")} disabled={locked} type="tel" inputMode="tel" autoComplete="tel" maxLength={20} placeholder="휴대폰 번호" value={fields.customerPhone} onChange={event => update("customerPhone", event.target.value)} required />{problem("customerPhone")}</div></div>
          <div className="inquiry-email"><label htmlFor="inquiry-customerEmail">이메일 <span>선택</span></label><input {...fieldProps("customerEmail")} disabled={locked} type="email" inputMode="email" autoComplete="email" maxLength={254} placeholder="이메일로 답변을 원하시면 입력" value={fields.customerEmail} onChange={event => update("customerEmail", event.target.value)} />{problem("customerEmail")}</div></div>
          <div className="inquiry-message"><label htmlFor="inquiry-message">문의 내용 <span>필수</span></label><textarea {...fieldProps("message")} disabled={locked} maxLength={2000} rows={5} placeholder="예: 원하는 묘목의 재고가 궁금해요." value={fields.message} onChange={event => update("message", event.target.value)} required />{problem("message")}</div>
          <p className="inquiry-private"><LockKeyhole aria-hidden="true" />판매자만 확인하는 비공개 문의예요.</p>
          <div className="inquiry-consent"><label htmlFor="inquiry-consent"><input {...fieldProps("consent")} type="checkbox" disabled={locked} checked={fields.consent} onChange={event => update("consent", event.target.checked)} required /><span>개인정보 수집·이용 동의</span></label><details><summary>자세히</summary><p>{inquiryPrivacyNotice}</p><a href="/privacy" target="_blank" rel="noopener noreferrer">개인정보 처리방침 · 새 창</a></details></div>{problem("consent")}
          <div hidden aria-hidden="true"><label>Website<input tabIndex={-1} autoComplete="off" value={fields.website} onChange={event => update("website", event.target.value)} /></label></div>
        </form>}
      </div>
      <div className="inquiry-footer">{receipt ? <Button type="button" className="inquiry-done" onClick={() => changeOpen(false)}><CheckCheck aria-hidden="true" />확인</Button> : <><div className="inquiry-actions"><Button type="button" variant="outline" onClick={() => changeOpen(false)}>{locked ? "닫기" : "취소"}</Button><Button type={!pending && ready !== true ? "button" : "submit"} form="customer-inquiry" aria-label={!pending && ready === false ? "온라인 문의 접수 준비 중 · 접수 가능 여부 다시 확인" : undefined} onClick={() => { if (!pending && ready !== true) void checkReady(); }} disabled={busy || conflict || (!pending && checking)}>{busy && <LoaderCircle className="animate-spin" aria-hidden="true" />}{busy ? "접수 확인 중…" : conflict ? "전화로 문의해주세요" : pending ? "같은 문의로 접수 확인" : checking || ready === null ? "접수 가능 여부 확인" : ready === false ? "접수 준비 중" : "문의 보내기"}</Button></div><p role="status">{ready === false ? "전화 상담을 이용해주세요." : "남겨주신 연락처로 답변드려요."}</p></>}
      <a className="inquiry-phone" href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone aria-hidden="true" />전화로 상담하기<ChevronRight aria-hidden="true" /></a></div>
    </DialogContent>
  </Dialog>;
}
