"use client";

import { useEffect, useRef, useState, type FormEvent, type MouseEvent } from "react";
import { Sprout, Plus, Minus, FileText, CheckCheck, MessageCircle, ShieldCheck, LoaderCircle, CircleCheck, X, Copy, Phone, ChevronDown, RotateCcw, Play } from "lucide-react";
import SpeciesBrowser from "@/components/species-browser";
import SpeciesPickerDialog from "@/components/species-picker-dialog";
import VideoLibraryDialog from "@/components/video-library-dialog";
import BlogLibraryDialog from "@/components/blog-library-dialog";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { quoteProcessingNotice } from "@/lib/privacy-content";
import { businessInfo } from "@/lib/business-info";
import { testNotice, testRequestMessage } from "@/lib/quote-test";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { quoteItemSchema, quoteSchema, normalizeQuotePhone, requestFingerprint, newRequestId, type QuoteRequest } from "@/lib/quote-schema";
import { validatedQuoteReceipt, receiptTimeSeoul, type QuoteReceipt } from "@/lib/quote-receipt";
import { retainPendingReceipt } from "@/lib/quote-upstream";
import { toggleCatalogLine, emptyLine, isEmptyLine, quoteFieldProblems, type FieldProblem, type RemovedQuoteLine, type QuoteLine as Line } from "@/lib/quote-form";
import type { VideoResource } from "@/lib/content-library";
import { createVideoQuoteDraft, stageVideoQuote, videoQuoteSpecies, videoQuoteSpeciesName, type VideoQuoteDraft } from "@/lib/video-quote";

const quantityPresets = [10, 20, 50, 100] as const;

export default function QuoteHome({ initialTestMode = false, initialConfigured = false }: { initialTestMode?: boolean; initialConfigured?: boolean }) {
  const [lines, setLines] = useState<Line[]>([emptyLine(0)]);
  const nextLine = useRef(1);
  const [pickerLineId, setPickerLineId] = useState<number | null>(null);
  const [name, setName] = useState(""); const [phone, setPhone] = useState("");
  const [region, setRegion] = useState(""); const [message, setMessage] = useState("");
  const [email, setEmail] = useState(""); const [formalQuote, setFormalQuote] = useState(false);
  const [recipient, setRecipient] = useState(""); const [replyChannel, setReplyChannel] = useState<"sms" | "email" | "both">("sms");
  const [website, setWebsite] = useState("");
  const [ready, setReady] = useState(initialConfigured);
  const [testMode, setTestMode] = useState(initialTestMode);
  const [testNoticeOpen, setTestNoticeOpen] = useState(initialTestMode);
  const [testAcknowledged, setTestAcknowledged] = useState(false);
  const [sampleOpen, setSampleOpen] = useState(false);
  const [error, setError] = useState(""); const [notice, setNotice] = useState("");
  const [review, setReview] = useState<QuoteRequest | null>(null);
  const [uncertain, setUncertain] = useState(false);
  const [reviewOpen, setReviewOpen] = useState(false);
  const [confirmedNotReceived, setConfirmedNotReceived] = useState(false);
  const [resetOpen, setResetOpen] = useState(false);
  const [resetTarget, setResetTarget] = useState<"form" | "home">("form");
  const [catalogRevision, setCatalogRevision] = useState(0);
  const returnHomeAfterReset = useRef(false);
  const homeLinkRef = useRef<HTMLAnchorElement>(null);
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false); const [success, setSuccess] = useState<QuoteReceipt | null>(null);
  const [menuOpen, setMenuOpen] = useState(false); const [atForm, setAtForm] = useState(false);
  const [videoOpen, setVideoOpen] = useState(false);
  const videoOpener = useRef<HTMLButtonElement>(null);
  const [videoQuoteOpen, setVideoQuoteOpen] = useState(false);
  const [videoQuoteSource, setVideoQuoteSource] = useState<VideoResource | null>(null);
  const [videoQuoteNotice, setVideoQuoteNotice] = useState("");
  const videoQuoteOpener = useRef<HTMLButtonElement>(null);
  const videoQuoteTitleRef = useRef<HTMLHeadingElement>(null);
  const videoQuoteDrafts = useRef(new Map<string, VideoQuoteDraft>());
  const mainQuoteDraft = useRef<VideoQuoteDraft | null>(null);
  const activeVideoQuote = useRef<string | null>(null);
  const [blogOpen, setBlogOpen] = useState(false);
  const blogOpener = useRef<HTMLButtonElement>(null);
  const heroBlogButton = useRef<HTMLButtonElement>(null);
  function openBlog(event: MouseEvent<HTMLButtonElement>) {
    blogOpener.current = event.currentTarget; setBlogOpen(true);
  }
  function openBlogFromMenu() {
    setMenuOpen(false); blogOpener.current = heroBlogButton.current;
    requestAnimationFrame(() => setBlogOpen(true));
  }
  function openVideos(event: MouseEvent<HTMLButtonElement>) {
    videoOpener.current = event.currentTarget; setVideoOpen(true);
  }
  const attempt = useRef<{ fingerprint: string; id: string } | null>(null);
  const formRef = useRef<HTMLFormElement>(null); const errorRef = useRef<HTMLDivElement>(null);
  const reviewTitleRef = useRef<HTMLHeadingElement>(null);
  const successRef = useRef<HTMLDivElement>(null);
  const [problems, setProblems] = useState<FieldProblem[]>([]);
  const [removed, setRemoved] = useState<{ lines: RemovedQuoteLine[]; label: string } | null>(null);
  const [delayed, setDelayed] = useState(false);
  const [conflict, setConflict] = useState(false);
  const [copyNotice, setCopyNotice] = useState("");
  const [successContact, setSuccessContact] = useState<{ phoneTail: string; channel: string; formal: boolean } | null>(null);
  const locked = busy || uncertain;

  useEffect(() => {
    // A pending receipt must keep its exact inputs until its outcome is known,
    // even if the customer closes the popup while waiting.
    if (videoQuoteOpen || locked || activeVideoQuote.current === null) return;
    const videoId = activeVideoQuote.current;
    if (success) videoQuoteDrafts.current.delete(videoId);
    else videoQuoteDrafts.current.set(videoId, { lines, message });
    activeVideoQuote.current = null;
    const main = mainQuoteDraft.current;
    mainQuoteDraft.current = null;
    if (main) { setLines(main.lines); setMessage(main.message); }
    setVideoQuoteSource(null); setVideoQuoteNotice(""); setPickerLineId(null);
    setNotice(""); setRemoved(null); setProblems([]); setError("");
    setReview(null); setReviewOpen(false); attempt.current = null;
  }, [videoQuoteOpen, locked, success, lines, message]);

  useEffect(() => {
    // A tab-scoped pending receipt survives reload. It is not the authoritative request store.
    try {
      const saved = JSON.parse(sessionStorage.getItem("namufarm.pending-request") || "null");
      const restored = quoteSchema.safeParse(saved?.request);
      if (restored.success && Date.now() - saved.createdAt < 86400000) {
        const v = restored.data;
        setName(v.customerName); setPhone(v.customerPhone); setRegion(v.region); setMessage(v.message); setEmail(v.customerEmail ?? ""); setFormalQuote(v.formalQuoteRequested ?? false); setRecipient(v.recipientName ?? ""); setReplyChannel(v.replyChannel ?? "sms");
        setLines(v.items.map(item => ({ ...item, id: nextLine.current++, quantity: String(item.quantity) })));
        const { requestId, ...payload } = v;
        attempt.current = { id: requestId, fingerprint: requestFingerprint(payload) };
        setReview(v); setUncertain(true); setReviewOpen(true);
      } else sessionStorage.removeItem("namufarm.pending-request");
    } catch { /* Storage may be blocked; no request is sent automatically. */ }
    const configAbort = new AbortController();
    fetch("/api/config", { cache: "no-store", signal: configAbort.signal }).then(r => {
      if (!r.ok) throw new Error("Config unavailable");
      return r.json() as Promise<{ ready?: boolean; privacyNotice?: string; testMode?: boolean }>;
    }).then(v => {
      setTestMode(v.testMode === true);
      if (v.testMode === true && !initialTestMode) setTestNoticeOpen(true);
      if (v.testMode !== true) setTestNoticeOpen(false);
      setReady(v.ready === true);
    }).catch(() => { /* Retain server-rendered settings when this refresh is interrupted. */ });
    return () => configAbort.abort();
  }, []);
  useEffect(() => {
    const observer = new IntersectionObserver(([entry]) => setAtForm(entry.isIntersecting), { threshold: 0 });
    if (formRef.current) observer.observe(formRef.current);
    return () => observer.disconnect();
  }, [videoQuoteOpen]);
  useEffect(() => { if (success) successRef.current?.focus(); }, [success]);
  useEffect(() => {
    if (!problems.length && error === "아래 항목을 확인해주세요.") setError("");
  }, [problems, error]);
  useEffect(() => {
    if (!busy) { setDelayed(false); return; }
    const timer = setTimeout(() => setDelayed(true), 15000);
    return () => clearTimeout(timer);
  }, [busy]);
  useEffect(() => {
    const context = (document as Document & { modelContext?: { registerTool: (tool: object, options: { signal: AbortSignal }) => unknown } }).modelContext;
    if (!context?.registerTool) return;
    const lifecycle = new AbortController();
    try {
      Promise.resolve(context.registerTool({
        name: "stage_quote_items", title: "묘목 견적 품목 준비",
        description: "견적 양식의 품목을 준비합니다. 전송하지 않으며 고객이 확인 후 접수해야 합니다.",
        inputSchema: { type: "object", properties: { items: { type: "array", minItems: 1, maxItems: 20, items: { type: "object", properties: { species: { type: "string" }, specification: { type: "string" }, quantity: { type: "integer", minimum: 1, maximum: 1000000 } }, required: ["species", "specification", "quantity"], additionalProperties: false } } }, required: ["items"], additionalProperties: false },
        annotations: { readOnlyHint: false, untrustedContentHint: false },
        execute(input: unknown) {
          if (inFlight.current || attempt.current) throw new Error("작성 중인 요청을 먼저 확인해주세요.");
          const items = quoteItemSchema.array().min(1).max(20).parse((input as { items?: unknown })?.items);
          setLines(items.map(item => ({ ...item, id: nextLine.current++, quantity: String(item.quantity) })));
          setSuccess(null); setNotice(`${items.length}개 품목을 준비했어요. 내용을 확인하고 연락처를 입력해주세요.`);
          document.getElementById("quote")?.scrollIntoView({ behavior: "smooth" });
          return { stagedItems: items.length, submitted: false };
        },
      }, { signal: lifecycle.signal })).catch(() => {});
    } catch { /* Optional browser capability. */ }
    return () => lifecycle.abort();
  }, []);

  const clearProblem = (id: string) => setProblems(old => old.filter(problem => problem.id !== id));
  function focusField(id: string) {
    requestAnimationFrame(() => document.getElementById(id)?.focus());
  }
  const updateLine = (id: number, key: keyof Omit<Line, "id">, value: string) => {
    if (locked) return;
    clearProblem(`${key === "specification" ? "spec" : key}-${id}`);
    setLines(old => old.map(line => line.id === id ? { ...line, [key]: value } : line));
  };
  function stepQuantity(id: number, step: -1 | 1) {
    if (locked) return;
    clearProblem("quantity-" + id);
    setLines(old => old.map(line => {
      if (line.id !== id) return line;
      const parsed = Number(line.quantity);
      const current = Number.isSafeInteger(parsed) && parsed >= 1 ? parsed : 0;
      return { ...line, quantity: String(Math.min(1000000, Math.max(1, current + step))) };
    }));
  }
  function choosePickedSpecies(species: string) {
    if (pickerLineId === null || locked) return;
    const id = pickerLineId;
    setLines(old => old.map(line => line.id === id
      ? { ...line, species, quantity: line.quantity || "1" }
      : line));
    setProblems(old => old.filter(problem => problem.id !== "species-" + id && problem.id !== "quantity-" + id));
    setNotice(species + " · 묘목 이름을 선택했어요.");
    setRemoved(null);
    setSuccess(null);
    setPickerLineId(null);
  }
  function selectSpecies(species: string) {
    if (locked) return;
    const result = toggleCatalogLine(lines, species, nextLine.current++);
    setLines(result.lines); setNotice(result.notice); setProblems([]);
    setRemoved(result.removed.length ? { lines: result.removed, label: species } : null);
    if (result.added) setSuccess(null);
  }
  function addVideoSpecies(video: VideoResource, species: string, focus = false) {
    const result = stageVideoQuote(lines, video, species, nextLine.current++, locked);
    setVideoQuoteNotice(result.notice);
    if (!result.staged) return;
    setLines(result.lines); setSuccess(null); setRemoved(null); setProblems([]); setError("");
    if (focus && result.lineId !== null) focusField(`quantity-${result.lineId}`);
  }
  function openVideoQuote(video: VideoResource, opener: HTMLButtonElement) {
    if (locked) return;
    const candidates = videoQuoteSpecies(video);
    if (!candidates.length) return;
    mainQuoteDraft.current = { lines, message };
    const draft = videoQuoteDrafts.current.get(video.videoId)
      ?? createVideoQuoteDraft(video, nextLine.current++, testMode ? 900 : 1000);
    activeVideoQuote.current = video.videoId;
    setLines(draft.lines); setMessage(draft.message);
    setNotice(""); setRemoved(null); setProblems([]); setError("");
    setReview(null); setReviewOpen(false); attempt.current = null;
    videoQuoteOpener.current = opener; setVideoQuoteSource(video); setVideoQuoteNotice(""); setSuccess(null);
    setVideoQuoteOpen(true);
  }
  function removeLine(id: number) {
    if (locked) return;
    const index = lines.findIndex(line => line.id === id);
    if (index < 0) return;
    setRemoved({ lines: [{ line: lines[index], index }], label: lines[index].species || "빈 품목" });
    setNotice(`${lines[index].species || "빈 품목"} · 목록에서 뺐어요.`);
    setLines(lines.length === 1 ? [emptyLine(nextLine.current++)] : lines.filter(line => line.id !== id));
    setProblems([]);
  }
  function undoRemove() {
    if (!removed || locked) return;
    const remaining = lines.filter(line => !isEmptyLine(line));
    const restore = removed.lines.filter(({ line }) => !remaining.some(current => current.id === line.id));
    if (remaining.length + restore.length > 20) { setNotice("20종까지 담을 수 있어요. 다른 품목을 뺀 뒤 되돌려주세요."); return; }
    for (const { line, index } of restore) remaining.splice(Math.min(index, remaining.length), 0, line);
    setLines(remaining.length ? remaining : [emptyLine(nextLine.current++)]); setRemoved(null); setNotice("품목을 되돌렸어요.");
  }
  function fieldAttrs(id: string, help?: string) {
    const invalid = problems.some(problem => problem.id === id);
    return { "aria-invalid": invalid || undefined, "aria-describedby": [help, invalid ? `${id}-error` : null].filter(Boolean).join(" ") || undefined };
  }
  function fieldError(id: string) {
    const problem = problems.find(value => value.id === id);
    return problem ? <p className="field-error" id={`${id}-error`}>{problem.message}</p> : null;
  }
  async function copyReceipt() {
    if (!success) return;
    try { await navigator.clipboard.writeText(success.receiptNumber); setCopyNotice("접수번호를 복사했어요."); }
    catch { setCopyNotice("접수번호를 길게 누르거나 선택해서 복사해주세요."); }
  }
  /** Reset local form inputs, leaving confirmed receipt display separate. */
  function clearQuoteInputs() {
    setName(""); setPhone(""); setRegion(""); setMessage(""); setEmail("");
    setFormalQuote(false); setRecipient(""); setReplyChannel("sms"); setWebsite("");
    setLines([emptyLine(nextLine.current++)]); setPickerLineId(null);
    if (activeVideoQuote.current && videoQuoteSource) {
      videoQuoteDrafts.current.delete(activeVideoQuote.current);
      const draft = createVideoQuoteDraft(videoQuoteSource, nextLine.current++, testMode ? 900 : 1000);
      setLines(draft.lines); setMessage(draft.message);
    }
    setNotice(""); setRemoved(null); setProblems([]);
  }
  function requestQuoteReset(target: "form" | "home") {
    setResetTarget(target); setConfirmedNotReceived(false); setResetOpen(true);
  }
  function resetQuoteForm() {
    if (busy || inFlight.current || (uncertain && !confirmedNotReceived)) return;
    try {
      sessionStorage.removeItem("namufarm.pending-request");
    } catch {
      if (uncertain) {
        setError("접수 확인 정보를 지우지 못했어요. 전화로 문의하거나 브라우저를 다시 열어주세요.");
        setResetOpen(false);
        return;
      }
    }
    attempt.current = null;
    returnHomeAfterReset.current = resetTarget === "home";
    setReview(null); setReviewOpen(false); setUncertain(false); setConflict(false);
    setConfirmedNotReceived(false); setResetOpen(false);
    setError(""); setSuccess(null); setSuccessContact(null); setCopyNotice("");
    clearQuoteInputs();
    videoQuoteDrafts.current.clear(); mainQuoteDraft.current = null; activeVideoQuote.current = null;
    setVideoQuoteOpen(false); setVideoQuoteSource(null); setVideoQuoteNotice("");
    setLines([emptyLine(nextLine.current++)]); setMessage("");
    setCatalogRevision(value => value + 1);
    setMenuOpen(false); setSampleOpen(false);
  }
  function prepare(event: FormEvent) {
    event.preventDefault(); if (locked) return; setError(""); setProblems([]); setConflict(false);
    if (testMode && !testAcknowledged) { setTestNoticeOpen(true); return; }
    const candidate = { requestId: newRequestId(), customerName: name, customerPhone: normalizeQuotePhone(phone), region, items: lines.map(({ species, specification, quantity }) => ({ species, specification, quantity: Number(quantity) })), message: testMode ? testRequestMessage(message) : message, processingBasis: "quote_request", website, customerEmail: formalQuote && replyChannel !== "sms" ? email : "", formalQuoteRequested: formalQuote, recipientName: formalQuote ? recipient : "", replyChannel: formalQuote ? replyChannel : "sms" };
    const parsed = quoteSchema.safeParse(candidate);
    if (!parsed.success) {
      const fields = quoteFieldProblems(parsed.error.issues, lines);
      setProblems(fields); setError("아래 항목을 확인해주세요.");
      focusField(fields[0]?.id || "quote-errors"); return;
    }
    const { requestId: _, ...payload } = parsed.data;
    const fingerprint = requestFingerprint(payload);
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, id: candidate.requestId };
    setReview({ ...parsed.data, requestId: attempt.current.id }); setReviewOpen(true);
  }
  async function submit() {
    if (!review || inFlight.current) return;
    if (testMode && !testAcknowledged) { setTestNoticeOpen(true); return; }
    const hadPending = uncertain;
    inFlight.current = true;
    setBusy(true); setError(""); setUncertain(true);
    try {
      sessionStorage.setItem("namufarm.pending-request", JSON.stringify({ request: review, createdAt: Date.now() }));
    } catch {
      // Do not submit unless a retry token can survive a reload in this tab.
      setError("이 브라우저에서는 접수 정보를 임시 보관할 수 없어요. 일반 브라우저 창에서 다시 열어주세요.");
      setUncertain(hadPending); setBusy(false); inFlight.current = false; return;
    }
    try {
      const response = await fetch("/api/quote-requests", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(review), signal: AbortSignal.timeout(120000) });
      const result = await response.json() as { requestId?: string; error?: string; receiptState?: string };
      const receipt = validatedQuoteReceipt(result, review.requestId);
      setConflict(response.status === 409);
      if (!response.ok && !retainPendingReceipt(hadPending, result)) {
        sessionStorage.removeItem("namufarm.pending-request"); setUncertain(false);
      }
      if (!response.ok || !receipt) throw new Error(result.error || "접수 결과를 확인하지 못했어요. 같은 내용으로 다시 보내주세요.");
      sessionStorage.removeItem("namufarm.pending-request");
      setSuccessContact({ phoneTail: review.customerPhone.slice(-4), channel: review.replyChannel || "sms", formal: review.formalQuoteRequested === true });
      setCopyNotice("");
      setUncertain(false); setSuccess(receipt); setReview(null); setReviewOpen(false); setConfirmedNotReceived(false); attempt.current = null;
      clearQuoteInputs();
    } catch (e) { setError(e instanceof Error && e.name !== "TimeoutError" && e.name !== "TypeError" && e.name !== "SyntaxError" ? e.message : "접수 결과를 확인하지 못했어요. 창을 닫아도 입력 내용은 유지됩니다. 잠시 후 같은 요청으로 확인해주세요."); }
    finally { setBusy(false); inFlight.current = false; }
  }
  function closeReview() {
    setReviewOpen(false);
    if (!locked) setReview(null);
  }
  function resumeEditing() {
    if (busy || !confirmedNotReceived) return;
    try { sessionStorage.removeItem("namufarm.pending-request"); }
    catch { setError("임시 정보를 정리하지 못했어요. 같은 요청으로 접수 확인을 이어가주세요."); return; }
    setReview(null); setReviewOpen(false); setUncertain(false); setConflict(false); setError("");
    setConfirmedNotReceived(false); attempt.current = null;
    setNotice("미접수 확인 후 내용을 다시 작성할 수 있어요.");
  }
  const count = lines.filter(line => line.species.trim()).length;
  const total = lines.reduce((n, line) => n + (Number.isSafeInteger(Number(line.quantity)) && Number(line.quantity) > 0 ? Number(line.quantity) : 0), 0);

  // One form instance shares validation, pending receipts and submission across both locations.
  const quoteForm = (
        <form className="quote-form" ref={formRef} onSubmit={prepare} noValidate aria-busy={busy}>
          {success ? <div className="success-panel" ref={successRef} tabIndex={-1} role="status"><CircleCheck /><span className="overline">접수 완료</span><h3>{testMode ? "테스트 요청을 저장했어요." : "견적 상담 요청이 접수됐어요."}</h3><p>{testMode ? "테스트 요청만 저장됐습니다. 실제 상담·견적서 발송은 진행하지 않습니다." : "판매자가 여러 농원의 재고와 규격을 확인한 뒤 남겨주신 연락처로 안내드립니다."}</p><div className="receipt"><span>견적접수번호</span><code>{success.receiptNumber}</code><Button type="button" variant="outline" onClick={copyReceipt}><Copy />접수번호 복사</Button><p className="copy-notice" role="status">{copyNotice}</p><span className="receipt-date-label">접수일시 · 한국시간</span><time dateTime={success.createdAt}>{receiptTimeSeoul(success.createdAt)}</time></div>{successContact && <p className="success-followup">상담 연락처: 끝 네 자리 <strong>{successContact.phoneTail}</strong><br />공식 견적서: {successContact.formal ? successContact.channel === "both" ? "이메일과 문자로 수신" : successContact.channel === "email" ? "이메일로 수신" : "문자로 수신" : "요청 안 함"}</p>}<p className="submit-note">아직 주문이나 결제가 확정된 것은 아닙니다.</p><Button type="button" variant="outline" onClick={() => { setSuccess(null); setNotice(""); }}>{testMode ? "다른 테스트 요청하기" : "다른 견적 요청하기"}</Button></div> : <>
            <div className="form-title"><div><FileText /><h3>견적 상담 요청</h3></div><span>아래 항목을 입력해주세요</span></div>
            <p className="form-call-help">입력이 어려우신가요? <a href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone aria-hidden="true" />전화 상담 · {businessInfo.mobile}</a></p>
            {uncertain && review && <section className="pending-request-notice service-notice" aria-label="접수 확인 대기">
              <strong>{busy ? "접수 결과를 기다리고 있어요" : "접수 여부 확인이 남아 있어요"}</strong>
              <p>창을 닫아도 같은 요청 정보가 유지됩니다. 이미 저장됐을 수 있으니 새 요청 전에 확인해주세요.</p>
              <Button type="button" variant="outline" onClick={() => setReviewOpen(true)}>{busy ? "진행 상황 보기" : "접수 확인 이어가기"}</Button>
              <a className="support-call" href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone />전화로 접수 확인 · {businessInfo.mobile}</a>
              {!busy && <details><summary>전화로 미접수를 확인하셨나요?</summary><p>접수 여부를 확인하지 않고 새로 보내면 중복될 수 있습니다. 접수 확인 정보만 지워지며 서버에 저장된 요청은 취소되지 않습니다.</p><label className="pending-confirm"><input type="checkbox" checked={confirmedNotReceived} onChange={e => setConfirmedNotReceived(e.target.checked)} />판매자에게 기존 요청이 접수되지 않았음을 확인했어요.</label><Button type="button" variant="outline" disabled={!confirmedNotReceived} onClick={resumeEditing}>내용 수정하고 다시 요청하기</Button></details>}
            </section>}
            {testMode && <div className="test-mode-notice"><strong>{testNotice}</strong><p>실제 고객정보 대신 테스트용 정보를 입력해주세요.</p><Button type="button" variant="link" onClick={() => setTestNoticeOpen(true)}>테스트 안내 다시 보기</Button></div>}
            {!ready && <div className="service-notice">온라인 접수를 준비하고 있어요. 지금은 양식을 살펴볼 수 있으며 요청은 전송되지 않습니다.</div>}
            {removed && <div className="undo-notice"><span>{removed.label} · 목록에서 뺐어요.</span><Button type="button" variant="ghost" onClick={undoRemove} disabled={locked}>되돌리기</Button></div>}
            <fieldset className="quote-section-box" disabled={locked}><legend><span>1</span>견적 요청 묘목</legend><div className="line-list">{lines.map((line, index) => <div className="item-line" key={line.id}>
              <div className="item-number"><span className="item-badge">품목 {index + 1}</span>{lines.length > 1 && <Button type="button" variant="ghost" className="item-remove-button" aria-label={`품목 ${index + 1} ${line.species} 빼기`} onClick={() => removeLine(line.id)}><X aria-hidden="true" />빼기</Button>}</div>
              <div className="item-fields">
                <div className="field species-field">
                  <Label htmlFor={"species-" + line.id} onClick={() => { if (!locked) focusField("species-" + line.id); }}>묘목 이름 <small>필수</small></Label>
                  <div className="species-select-row">
                    <Input id={"species-" + line.id} required maxLength={80} placeholder="묘목 이름 직접 입력" value={line.species} onChange={e => updateLine(line.id, "species", e.target.value)} {...fieldAttrs("species-" + line.id)} />
                    <Button id={"choose-species-" + line.id} type="button" className="species-select-button" aria-haspopup="dialog" aria-label={"품목 " + (index + 1) + " 초성으로 묘목 선택"} onClick={() => setPickerLineId(line.id)}>
                      <span>초성검색</span><ChevronDown className="species-picker-chevron" aria-hidden="true" />
                    </Button>
                  </div>
                  {fieldError("species-" + line.id)}
                </div>
                <div className="field quantity-field">
                  <Label htmlFor={"quantity-" + line.id}>수량 <small>필수 · 주 단위</small></Label>
                  <div className="quantity-stepper">
                    <Button type="button" className="quantity-step-button" aria-label={"품목 " + (index + 1) + " 수량 줄이기"} disabled={!line.quantity || Number(line.quantity) <= 1} onClick={() => stepQuantity(line.id, -1)}>
                      <Minus className="size-5" aria-hidden="true" />
                    </Button>
                    <Input id={"quantity-" + line.id} className="quantity-value" required inputMode="numeric" type="number" min="1" max="1000000" step="1" placeholder="예: 100" value={line.quantity} onChange={e => updateLine(line.id, "quantity", e.target.value)} {...fieldAttrs("quantity-" + line.id)} />
                    <Button type="button" className="quantity-step-button" aria-label={"품목 " + (index + 1) + " 수량 늘리기"} disabled={Number(line.quantity) >= 1000000} onClick={() => stepQuantity(line.id, 1)}>
                      <Plus className="size-5" aria-hidden="true" />
                    </Button>
                    <span className="quantity-unit">주</span>
                  </div>
                  <div className="quantity-quick-select">
                    <p>수량 빠른선택</p>
                    <div className="quantity-presets" role="group" aria-label={`품목 ${index + 1} 수량 빠른선택`}>
                      {quantityPresets.map(quantity => <Button key={quantity} type="button" variant="outline" aria-label={`품목 ${index + 1} 수량 ${quantity}주로 설정`} aria-pressed={Number(line.quantity) === quantity} onClick={() => updateLine(line.id, "quantity", String(quantity))}>{quantity}주</Button>)}
                    </div>
                  </div>
                  {fieldError("quantity-" + line.id)}
                </div>
              </div><div className="field spec-field"><Label htmlFor={`spec-${line.id}`}>희망 규격 <small>선택</small></Label><Input id={`spec-${line.id}`} maxLength={120} placeholder="예: 2년생, 키 1m" value={line.specification} onChange={e => updateLine(line.id, "specification", e.target.value)} {...fieldAttrs(`spec-${line.id}`)} />{fieldError(`spec-${line.id}`)}</div>
            </div>)}</div><Button className="add-line" type="button" variant="outline" disabled={lines.length >= 20} onClick={() => { const id = nextLine.current++; setLines(old => [...old, emptyLine(id)]); setNotice(""); focusField(`species-${id}`); }}><Plus />다른 묘목 추가</Button></fieldset>
            <fieldset className="quote-section-box" disabled={locked}><legend><span>2</span>상담받을 분 · 연락처</legend><div className="contact-grid">
              <div className="field"><Label htmlFor="name">성함 <small>필수</small></Label><Input id="name" autoComplete="name" required minLength={2} maxLength={50} placeholder="성함을 적어주세요" value={name} onChange={e => { setName(e.target.value); clearProblem("name"); }} {...fieldAttrs("name")} />{fieldError("name")}</div>
              <div className="field"><Label htmlFor="phone">전화번호 <small>필수</small></Label><Input id="phone" type="tel" autoComplete="tel" inputMode="tel" required maxLength={20} placeholder="010-1234-5678" value={phone} onChange={e => { setPhone(e.target.value); clearProblem("phone"); }} {...fieldAttrs("phone")} />{fieldError("phone")}</div>
              <div className="field full"><Label htmlFor="region">배송지 <small>선택</small></Label><Input id="region" autoComplete="address-level2" maxLength={100} placeholder="예: 충북 옥천군" value={region} onChange={e => { setRegion(e.target.value); clearProblem("region"); }} {...fieldAttrs("region")} />{fieldError("region")}</div>
              <div className="field full"><Label htmlFor="message">궁금한 점이나 요청사항 <small>선택</small></Label><Textarea id="message" maxLength={testMode ? 900 : 1000} placeholder="심을 장소, 희망 배송 시기 등을 남겨주세요." value={message} onChange={e => { setMessage(e.target.value); clearProblem("message"); }} rows={3} {...fieldAttrs("message")} />{fieldError("message")}</div>
            </div></fieldset>
            <fieldset className="quote-section-box formal-quote-options" disabled={locked}><legend><span>3</span>공식 견적서 <small>선택</small></legend>
              <label className="quote-option-check"><input type="checkbox" checked={formalQuote} onChange={e => { setFormalQuote(e.target.checked); setProblems(old => old.filter(p => !["email", "recipient"].includes(p.id))); }} /><span>공식 견적서도 받고 싶어요</span></label><p className="field-help">회사나 기관에 제출할 견적서가 필요하면 선택하세요. 상담 후 판매자가 작성해 보내드립니다.</p><Button type="button" variant="ghost" className="sample-help-button" onClick={() => setSampleOpen(true)}><FileText />견적서 예시 보기</Button>
              {formalQuote && <div className="formal-fields"><fieldset className="reply-options"><legend>견적서 받는 방법</legend>{[["sms", "문자로 이미지 받기"], ["email", "이메일로 PDF 받기"], ["both", "이메일과 문자 둘 다"]].map(([value, label]) => <label key={value}><input type="radio" name="reply-channel" value={value} checked={replyChannel === value} onChange={() => { setReplyChannel(value as "sms" | "email" | "both"); clearProblem("email"); }} /><span>{label}</span></label>)}</fieldset>
                {replyChannel !== "sms" && <div className="field"><Label htmlFor="email">견적서 받을 이메일 <small>필수</small></Label><Input id="email" type="email" autoComplete="email" inputMode="email" maxLength={254} required placeholder="예: name@example.com" value={email} onChange={e => { setEmail(e.target.value); clearProblem("email"); }} {...fieldAttrs("email")} />{fieldError("email")}</div>}
                <div className="field"><Label htmlFor="recipient">견적서 수신인·회사명 <small>선택</small></Label><Input id="recipient" maxLength={100} placeholder="예: ○○회사" value={recipient} onChange={e => { setRecipient(e.target.value); clearProblem("recipient"); }} {...fieldAttrs("recipient", "recipient-help")} /><p id="recipient-help" className="field-help">비워두면 입력하신 성함으로 작성해요.</p>{fieldError("recipient")}</div>
              </div>}
            </fieldset>
            <div className="honeypot" aria-hidden="true"><label htmlFor="website">Website</label><input id="website" tabIndex={-1} autoComplete="off" value={website} onChange={e => setWebsite(e.target.value)} /></div>
            <div className="processing-notice"><p>{quoteProcessingNotice}</p><a href="/privacy">개인정보 처리방침</a></div>
            <div id="quote-errors" ref={errorRef} tabIndex={-1} className={error ? "form-error" : ""} role={error ? "alert" : undefined}>{error}{problems.length > 0 && <ul>{problems.map(problem => <li key={problem.id}><button type="button" onClick={() => focusField(problem.id)}>{problem.message}</button></li>)}</ul>}</div>
            <div className="form-summary"><span>요청 품목 <strong>{count}종</strong></span><span>총 수량 <strong>{total.toLocaleString()}주</strong></span></div>
            <div className="quote-submit-actions">
              <Button className="submit-button" type="submit" disabled={busy} onClick={uncertain ? event => { event.preventDefault(); setReviewOpen(true); } : undefined}>{uncertain ? "접수 확인 이어가기" : "내용 확인하기"}</Button>
              <Button className="quote-reset-button" type="button" variant="outline" disabled={busy}
                onClick={() => requestQuoteReset("form")}>
                <RotateCcw aria-hidden="true" />초기화
              </Button>
            </div>
            <p className="submit-note">주문과 결제는 상담 후 결정합니다.</p>
          </>}
        </form>
  );

  return <>
    <a className="skip-link" href="#quote">견적 요청으로 바로 가기</a>
    <header className="site-header"><div className="header-inner">
      <a href="#home" ref={homeLinkRef} className="brand" aria-label="나무팜 홈 · 견적 입력 초기화" aria-haspopup="dialog" onClick={event => { event.preventDefault(); requestQuoteReset("home"); }}><Sprout strokeWidth={2} /><span>나무팜<small>NAMUFARM</small></span></a>
      <nav className="desktop-nav" aria-label="주 메뉴"><a href="#trees">묘목 찾기</a><button type="button" className="video-nav-button" onClick={openVideos} aria-haspopup="dialog">묘목 재배 영상</button><button type="button" className="video-nav-button" onClick={openBlog} aria-haspopup="dialog">묘목 블로그</button><a href="#guide">이용 안내</a><a href="#questions">자주 묻는 질문</a></nav>
      <Button asChild variant="outline" className="header-call"><a href={`tel:${businessInfo.mobile.replaceAll("-", "")}`} aria-label={`전화 상담 ${businessInfo.mobile}`}><Phone aria-hidden="true" />전화 상담</a></Button>
      <Button asChild className="header-quote"><a href="#quote">견적 상담 요청</a></Button>
      <Button type="button" variant="outline" className="header-videos" aria-label="묘목 재배 영상 보기" aria-haspopup="dialog" onClick={openVideos}>재배 영상</Button>
      <Dialog open={menuOpen} onOpenChange={setMenuOpen}><DialogTrigger asChild><Button variant="ghost" className="menu-button" aria-label="메뉴 열기">메뉴</Button></DialogTrigger><DialogContent className="menu-dialog" showCloseButton={false}><DialogClose asChild><Button className="dialog-dismiss" variant="ghost" aria-label="메뉴 닫기"><X /></Button></DialogClose><DialogHeader><DialogTitle>나무팜</DialogTitle><DialogDescription>묘목 찾기와 견적 상담</DialogDescription></DialogHeader><nav aria-label="모바일 메뉴"><button type="button" onClick={openBlogFromMenu} aria-haspopup="dialog">묘목 정보·블로그 보기</button>{[["trees", "묘목 찾기"], ["quote", "견적 상담 요청"], ["guide", "이용 안내"], ["questions", "자주 묻는 질문"]].map(([id, title]) => <a key={id} href={`#${id}`} onClick={() => setMenuOpen(false)}>{title}</a>)}<a href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone aria-hidden="true" />전화 상담 · {businessInfo.mobile}</a></nav></DialogContent></Dialog>
    </div></header>
    <main id="home">
      <section className="hero" aria-labelledby="hero-title">
        <img src="/nursery-hero.webp" alt="햇살 아래 자라는 어린 묘목" width="1672" height="940" fetchPriority="high" className="hero-image" /><div className="hero-shade" />
        <div className="hero-inner">
          <span className="eyebrow">충북 옥천 · 묘목 견적 상담</span>
          <h1 id="hero-title">심고 싶은 나무,<br />견적부터 <em>편하게.</em></h1>
          <p>원하는 묘목을 고르고 수량을 알려주세요.<br />규격과 배송은 상담을 통해 안내해 드립니다.</p>
          <div className="hero-actions"><Button asChild className="hero-cta"><a href="#trees"><Sprout />묘목 찾아보기</a></Button><a className="hero-direct" href="#quote">견적 바로 작성 →</a></div>
          <div className="hero-resource-actions"><Button type="button" variant="outline" className="hero-videos" aria-haspopup="dialog" onClick={openVideos}><Play aria-hidden="true" />묘목 재배 영상 보기</Button><Button type="button" variant="outline" className="hero-videos" aria-haspopup="dialog" ref={heroBlogButton} onClick={openBlog}><FileText aria-hidden="true" />묘목 정보·블로그 보기</Button></div>
          <div className="hero-highlights" aria-label="견적 상담 특징"><span>회원가입 없이 요청</span><span>규격 상담 가능</span><span>결제는 상담 후 결정</span></div>
        </div>
      </section>
      <div className="journey-strip wrap" aria-label="견적 상담 순서"><span><b>1</b>묘목 고르기</span><span><b>2</b>수량·연락처 입력</span><span><b>3</b>상담 후 결정</span></div>
      <SpeciesBrowser key={catalogRevision} lines={lines} onSelect={selectSpecies} onRemove={removeLine} notice={notice} locked={locked} />
      <section id="quote" className="quote-section" aria-labelledby="quote-title quote-mobile-title"><div className="quote-layout wrap">
        <div className="quote-mobile-intro">
          <h2 id="quote-mobile-title">묘목 견적 상담</h2>
          <p>묘목과 수량을 알려주세요.</p>
          <details className="quote-writing-guide"><summary>작성 안내 보기 <ChevronDown aria-hidden="true" /></summary><div><p>수량은 주 단위로 적어주세요.</p><p>규격을 모르면 비워두세요. 상담하면서 확인합니다.</p><a href="#trees">묘목 더 찾아보기</a></div></details>
          <div className="quote-mobile-promise"><ShieldCheck aria-hidden="true" /><p>견적 요청만으로 <strong>주문이 확정되지 않아요.</strong></p></div>
        </div>
        <aside className="quote-intro"><span className="overline">묘목 견적 상담</span><h2 id="quote-title">필요한 만큼,<br />함께 알아볼게요.</h2><p>수량은 <strong>주 단위</strong>로 적어주세요.<br />규격을 몰라도 괜찮습니다.</p><div className="intro-promise"><ShieldCheck /><p>견적 요청만으로<br /><strong>주문이나 결제가 확정되지 않아요.</strong></p></div><a href="#trees" className="back-to-trees">묘목 더 찾아보기</a></aside>
        {videoQuoteOpen ? <p className="quote-popup-placeholder">견적 팝업에서 작성 중입니다. 입력 내용은 함께 유지됩니다.</p> : quoteForm}
      </div></section>
      <section id="guide" className="guide-section wrap" aria-labelledby="guide-title"><div className="section-heading"><div><span className="overline">이용 안내</span><h2 id="guide-title">나무를 만나는 세 단계</h2></div><p>회원가입 없이 편하게 문의하세요.</p></div><div className="steps">{[{ icon: Sprout, title: "묘목과 수량 선택", text: "여러 묘목을 함께 담고, 필요한 수량을 주 단위로 적어주세요." }, { icon: FileText, title: "견적 상담 요청", text: "연락처와 배송 지역을 남기면 접수 완료 화면에서 번호를 안내해요." }, { icon: MessageCircle, title: "상담 후 주문 결정", text: "여러 농원의 재고와 규격, 배송비를 안내받고 상담한 뒤 결정하세요." }].map((step, i) => <article className="step" key={step.title}><div className="step-top"><step.icon /><span>0{i + 1}</span></div><h3>{step.title}</h3><p>{step.text}</p></article>)}</div></section>
      <section id="questions" className="faq-section wrap"><div><span className="overline">자주 묻는 질문</span><h2>문의 전에<br />궁금하셨나요?</h2></div><Accordion type="single" collapsible className="faq-list"><AccordionItem value="size"><AccordionTrigger>묘목 규격을 몰라도 요청할 수 있나요?</AccordionTrigger><AccordionContent>네. 묘목 이름과 필요한 수량만 적어주세요. 희망 규격은 비워두셔도 됩니다. 심을 장소와 용도를 요청사항에 남겨주시면 상담에 도움이 됩니다.</AccordionContent></AccordionItem><AccordionItem value="order"><AccordionTrigger>견적을 요청하면 바로 주문되나요?</AccordionTrigger><AccordionContent>견적 요청은 상담을 위한 문의입니다. 재고, 규격, 금액과 배송 방법을 확인하고 주문을 결정하시면 됩니다.</AccordionContent></AccordionItem><AccordionItem value="delivery"><AccordionTrigger>배송비와 배송 날짜는 어떻게 정하나요?</AccordionTrigger><AccordionContent>묘목 크기, 수량과 배송 지역에 따라 달라집니다. 접수한 요청을 확인한 후 가능한 배송 방법과 비용, 일정을 안내드립니다.</AccordionContent></AccordionItem><AccordionItem value="more"><AccordionTrigger>목록에 없는 묘목도 문의할 수 있나요?</AccordionTrigger><AccordionContent>네. 견적 요청란에 원하는 묘목 이름을 직접 적어주세요. 공급 가능 여부는 상담을 통해 확인합니다.</AccordionContent></AccordionItem></Accordion></section>
    </main>
    <footer className="site-footer"><div className="wrap footer-inner"><div><div className="footer-brand-row"><a href="#home" className="brand" aria-label="나무팜 홈 · 견적 입력 초기화" aria-haspopup="dialog" onClick={event => { event.preventDefault(); requestQuoteReset("home"); }}><Sprout /><span>나무팜<small>NAMUFARM</small></span></a><div className="footer-business"><span>{businessInfo.name} · 대표 {businessInfo.representative}</span><span>사업자등록번호 {businessInfo.number}</span></div></div><p>당신의 내일에, 푸르름을 더합니다.</p></div><div><a href="#quote">묘목 견적 상담</a><div className="footer-phone-row"><a href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}>전화 상담 {businessInfo.mobile}</a><span aria-hidden="true">/</span><a href={`tel:${businessInfo.phone.replaceAll("-", "")}`}>대표전화 {businessInfo.phone}</a></div><a href="/privacy">개인정보 처리방침</a><small>© {new Date().getFullYear()} NAMUFARM</small></div></div></footer>
    <div className={`mobile-cta ${atForm || reviewOpen || success || menuOpen || videoOpen || videoQuoteOpen || blogOpen || pickerLineId !== null || sampleOpen || testNoticeOpen ? "is-hidden" : ""}`} role="region" aria-label="빠른 상담"><span><strong>{count ? `담은 묘목 ${count}종` : "필요한 묘목을 알려주세요"}</strong><small>{count ? "계속 고르셔도 됩니다" : "결제 없이 견적 상담"}</small></span><div className="mobile-cta-actions"><Button asChild variant="outline" className="mobile-call"><a href={`tel:${businessInfo.mobile.replaceAll("-", "")}`} aria-label={`전화 상담 ${businessInfo.mobile}`}><Phone aria-hidden="true" />전화 상담</a></Button><Button asChild><a href="#quote">{count ? `수량 입력 (${count}종)` : "견적 바로 작성"}</a></Button></div></div>
    <VideoLibraryDialog open={videoOpen} onOpenChange={setVideoOpen} opener={videoOpener} onRequestQuote={openVideoQuote} quoteOpen={videoQuoteOpen} quoteLocked={locked} />
    <Dialog open={videoQuoteOpen} onOpenChange={setVideoQuoteOpen}>
      <DialogContent className="video-library-dialog video-quote-dialog" showCloseButton={false}
        onOpenAutoFocus={event => { event.preventDefault(); videoQuoteTitleRef.current?.focus(); }}
        onCloseAutoFocus={event => { event.preventDefault(); const button = videoQuoteOpener.current; if (button && !button.disabled) button.focus({ preventScroll: true }); else button?.closest(".video-detail")?.querySelector<HTMLElement>("h3")?.focus({ preventScroll: true }); }}>
        <div className="video-library-heading">
          <DialogHeader><DialogTitle ref={videoQuoteTitleRef} tabIndex={-1}>영상 속 묘목 견적</DialogTitle><DialogDescription>묘목과 수량, 연락처를 확인하고 요청해주세요.</DialogDescription></DialogHeader>
          <Button type="button" variant="outline" className="video-dismiss" onClick={() => setVideoQuoteOpen(false)} aria-label="견적 팝업 닫고 영상으로 돌아가기"><X aria-hidden="true" />견적 닫기</Button>
        </div>
        <div className="video-library-body video-quote-body">
          {videoQuoteSource && !success && <section className="video-quote-context" aria-label="영상 관련 묘목 선택">
            <p className="video-quote-source-title">참고 영상 · {videoQuoteSource.title}</p>
            <p>{videoQuoteSpecies(videoQuoteSource).length > 1 ? "영상에 나온 묘목 중 요청할 묘목을 골라주세요." : "영상에 분류된 묘목입니다. 이름과 희망 규격을 확인해주세요."} 묘목과 요청사항은 영상별로 따로 작성됩니다. 연락처는 함께 사용합니다.</p>
            <div className="video-quote-candidates">{videoQuoteSpecies(videoQuoteSource).map(species => {
              const added = lines.some(line => videoQuoteSpeciesName(line.species) === species);
              return <Button type="button" key={species} variant="outline" disabled={locked} aria-pressed={added} onClick={() => addVideoSpecies(videoQuoteSource, species, true)}>{added ? <CheckCheck aria-hidden="true" /> : <Plus aria-hidden="true" />}{species} · {added ? "담김" : "담기"}</Button>;
            })}</div>
            {videoQuoteNotice && <p className="video-quote-notice" role="status">{videoQuoteNotice}</p>}
          </section>}
          {videoQuoteOpen && quoteForm}
        </div>
      </DialogContent>
    </Dialog>
    <BlogLibraryDialog open={blogOpen} onOpenChange={setBlogOpen} opener={blogOpener} />
    <SpeciesPickerDialog open={pickerLineId !== null && !locked} onOpenChange={open => { if (!open) setPickerLineId(null); }} onChoose={choosePickedSpecies} selectedName={lines.find(line => line.id === pickerLineId)?.species ?? ""} />
    <Dialog open={resetOpen} onOpenChange={open => { setResetOpen(open); if (!open) setConfirmedNotReceived(false); }}>
      <DialogContent className="quote-reset-dialog" showCloseButton={false} onCloseAutoFocus={event => {
        if (!returnHomeAfterReset.current) return;
        event.preventDefault(); returnHomeAfterReset.current = false;
        window.history.replaceState(window.history.state, "", "#home");
        homeLinkRef.current?.focus({ preventScroll: true });
        window.scrollTo(0, 0);
      }}>
        <DialogHeader>
          <DialogTitle>{resetTarget === "home" ? "초기화하고 처음 화면으로 돌아갈까요?" : "견적 입력을 초기화할까요?"}</DialogTitle>
          <DialogDescription>{uncertain
            ? "접수 결과가 확인되지 않았습니다. 이미 접수된 요청이 있을 수 있으므로 초기화 후 다시 전송하면 중복될 수 있어요."
            : resetTarget === "home" ? "담은 묘목과 작성 중인 견적 정보를 모두 지우고 처음 화면으로 돌아갑니다." : "작성 중인 묘목·수량·연락처가 모두 지워집니다."}</DialogDescription>
        </DialogHeader>
        {busy && <p className="service-notice" role="status">접수 결과를 확인하고 있어요. 확인이 끝나면 초기화할 수 있습니다.</p>}
        {uncertain && <>
          <p className="service-notice">판매자에게 기존 요청이 접수되지 않았는지 먼저 확인해주세요. 초기화해도 서버에 저장된 내용은 삭제되지 않습니다.</p>
          <a className="support-call" href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone aria-hidden="true" />전화로 접수 확인 · {businessInfo.mobile}</a>
          <label className="pending-confirm">
            <input type="checkbox" checked={confirmedNotReceived} onChange={e => setConfirmedNotReceived(e.target.checked)} />
            판매자에게 기존 요청이 접수되지 않았음을 확인했어요.
          </label>
        </>}
        <div className="quote-reset-dialog-actions">
          <Button type="button" variant="outline" onClick={() => { setResetOpen(false); setConfirmedNotReceived(false); }}>취소</Button>
          <Button type="button" disabled={busy || (uncertain && !confirmedNotReceived)} onClick={resetQuoteForm}>입력 초기화</Button>
        </div>
        <p className="submit-note">초기화는 홈페이지 입력만 지우며 서버에 저장된 견적은 삭제하지 않습니다.</p>
      </DialogContent>
    </Dialog>
    <Dialog open={reviewOpen && !!review} onOpenChange={open => { if (!open) closeReview(); }}><DialogContent className="review-dialog" onOpenAutoFocus={event => { event.preventDefault(); reviewTitleRef.current?.focus(); }} showCloseButton={false}>
      <DialogClose asChild><Button className="dialog-dismiss" type="button" variant="ghost" aria-label="접수 확인창 닫기"><X /></Button></DialogClose>
      <DialogHeader><DialogTitle ref={reviewTitleRef} tabIndex={-1}>{testMode ? "테스트 요청을 확인해주세요" : "견적 상담을 요청할까요?"}</DialogTitle><DialogDescription>{testMode ? testNotice : "품목·수량·연락처를 확인해주세요. 주문과 결제는 상담 후 결정합니다."}</DialogDescription></DialogHeader>
      {review && <><section className="review-section-box" aria-labelledby="review-items-title"><h3 id="review-items-title">견적 요청 묘목</h3><div className="review-items">{review.items.map((item, i) => <div key={i}><span><strong>{item.species}</strong><small>{item.specification || "규격 상담 희망"}</small></span><b>{item.quantity.toLocaleString()}주</b></div>)}</div></section><section className="review-section-box" aria-labelledby="review-contact-title"><h3 id="review-contact-title">상담받을 분 · 연락처</h3><dl className="review-contact"><dt>성함</dt><dd>{review.customerName}</dd><dt>전화번호</dt><dd>{review.customerPhone}</dd><dt>배송지</dt><dd>{review.region || "미입력"}</dd>{review.customerEmail && <><dt>이메일</dt><dd>{review.customerEmail}</dd></>}<dt>공식 견적서</dt><dd>{review.formalQuoteRequested ? review.replyChannel === "email" ? "이메일 · PDF" : review.replyChannel === "both" ? "이메일과 문자" : "문자 · 이미지" : "요청 안 함"}</dd>{review.formalQuoteRequested && <><dt>견적서 수신인</dt><dd>{review.recipientName || review.customerName}</dd></>}{review.message && <><dt>요청사항</dt><dd>{review.message}</dd></>}</dl></section>
        {!ready && <p className="service-notice">온라인 접수를 준비하고 있어요. 현재 요청은 전송할 수 없습니다.</p>}
        <div role="status" aria-live="polite">{busy ? <p className="service-notice">{delayed ? "확인이 늦어지고 있어요. 창을 닫아도 접수 확인은 계속됩니다. 결과가 확인되지 않으면 같은 요청으로 다시 확인해주세요." : "접수하고 있어요. 창을 닫아도 이 페이지에서 결과를 확인할 수 있어요."}</p> : uncertain && !conflict && <p className="service-notice">이미 저장됐을 수 있어요. 창을 닫아도 입력 내용과 접수 확인 정보는 유지됩니다. 같은 요청으로 다시 확인하면 중복 접수를 방지할 수 있어요.</p>}</div>
        {conflict && <div className="form-error" role="alert"><p>저장된 요청과 내용이 달라 자동 재확인을 멈췄어요. 새 요청을 만들기 전에 전화로 접수 여부를 확인해주세요.</p><a className="support-call" href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone />{businessInfo.mobile}</a><details><summary>상담 시 알려줄 확인번호</summary><code>{review.requestId}</code></details></div>}
        {error && !conflict && <p className="form-error" role="alert">{error}</p>}
        {uncertain && !busy && !conflict && error && <a className="support-call" href={`tel:${businessInfo.mobile.replaceAll("-", "")}`}><Phone />전화로 접수 확인 · {businessInfo.mobile}</a>}
        <div className="review-actions"><Button className="submit-button" type="button" disabled={busy || !ready || conflict} onClick={submit}>{busy ? <LoaderCircle className="animate-spin" /> : <CheckCheck />}{busy ? "접수 확인 중…" : conflict ? "전화로 접수 여부를 확인해주세요" : uncertain ? "같은 요청으로 접수 확인" : testMode ? "테스트 요청 보내기" : "견적 상담 요청 보내기"}</Button><Button type="button" variant="outline" onClick={closeReview}>{locked ? "닫기 · 나중에 확인" : "뒤로 · 내용 수정"}</Button></div><p className="submit-note">{testMode ? "테스트 요청만 저장되며 실제 견적·주문으로 처리하지 않습니다." : "접수번호가 나오면 저장이 확인됩니다. 오류가 나더라도 이미 저장됐을 수 있습니다."}</p>
      </>}
    </DialogContent></Dialog>
    <Dialog open={testNoticeOpen} onOpenChange={setTestNoticeOpen}><DialogContent className="test-notice-dialog" showCloseButton={false}><DialogHeader><DialogTitle>견적 요청 테스트 안내</DialogTitle><DialogDescription>{testNotice}</DialogDescription></DialogHeader><p>연결 확인용으로만 저장하며, 실제 상담·견적서 발송·구매·배송은 진행하지 않습니다.</p><p>실제 고객정보 대신 테스트용 이름과 연락처를 사용해주세요.</p><Button type="button" onClick={() => { setTestAcknowledged(true); setTestNoticeOpen(false); }}>확인 · 테스트 양식 보기</Button></DialogContent></Dialog>
    <Dialog open={sampleOpen} onOpenChange={setSampleOpen}><DialogContent className="quote-sample-dialog" showCloseButton={false}><DialogClose asChild><Button className="dialog-dismiss" variant="ghost" aria-label="견적서 예시 닫기"><X /></Button></DialogClose><DialogHeader><DialogTitle>공식 견적서 예시</DialogTitle><DialogDescription>상담 후 판매자가 작성하는 견적서의 예시입니다. 아래 품목·수량·금액은 실제 견적이 아닙니다.</DialogDescription></DialogHeader><div className="quote-sample-preview"><img src="/quote-sample.webp" alt="나무팜농원 견적서 예시. 블루베리와 감나무 예시 품목, 수신 업체 정보 비공개, SAMPLE 워터마크." width="1190" height="1684" loading="lazy" /></div><Button asChild variant="outline"><a href="/quote-sample.pdf" target="_blank" rel="noopener noreferrer">견적서 예시 PDF 보기 · 새 창</a></Button></DialogContent></Dialog>
  </>;
}
