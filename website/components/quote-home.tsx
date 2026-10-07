"use client";

import { useEffect, useRef, useState, type FormEvent } from "react";
import { Sprout, Plus, Trash2, FileText, CheckCheck, MessageCircle, MapPin, ShieldCheck, LoaderCircle, Menu, CircleCheck } from "lucide-react";
import SpeciesBrowser from "@/components/species-browser";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import { quoteProcessingNotice } from "@/lib/privacy-content";
import { businessInfo } from "@/lib/business-info";
import { testNotice, testRequestMessage } from "@/lib/quote-test";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion";
import { quoteItemSchema, quoteSchema, requestFingerprint, newRequestId, type QuoteRequest } from "@/lib/quote-schema";
import { validatedQuoteReceipt, receiptTimeSeoul, type QuoteReceipt } from "@/lib/quote-receipt";

type Line = { id: number; species: string; specification: string; quantity: string };
const emptyLine = (id: number): Line => ({ id, species: "", specification: "", quantity: "" });

export default function QuoteHome({ initialTestMode = false, initialConfigured = false }: { initialTestMode?: boolean; initialConfigured?: boolean }) {
  const [lines, setLines] = useState<Line[]>([emptyLine(0)]);
  const nextLine = useRef(1);
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
  const inFlight = useRef(false);
  const [busy, setBusy] = useState(false); const [success, setSuccess] = useState<QuoteReceipt | null>(null);
  const [menuOpen, setMenuOpen] = useState(false); const [atForm, setAtForm] = useState(false);
  const attempt = useRef<{ fingerprint: string; id: string } | null>(null);
  const formRef = useRef<HTMLFormElement>(null); const errorRef = useRef<HTMLDivElement>(null);

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
        setReview(v); setUncertain(true);
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
    const observer = new IntersectionObserver(([entry]) => setAtForm(entry.isIntersecting), { threshold: 0.05 });
    if (formRef.current) observer.observe(formRef.current);
    return () => { configAbort.abort(); observer.disconnect(); };
  }, []);
  useEffect(() => { if (error) errorRef.current?.focus(); }, [error]);
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

  const updateLine = (id: number, key: keyof Omit<Line, "id">, value: string) => setLines(old => old.map(line => line.id === id ? { ...line, [key]: value } : line));
  function selectSpecies(species: string) {
    if (lines.some(line => line.species === species)) setNotice(`${species}는 이미 담겨 있어요. 수량을 입력해주세요.`);
    else if (lines.length >= 20) setNotice("한 번에 20개 품목까지 요청할 수 있어요.");
    else {
      setLines(old => old.length === 1 && !old[0].species && !old[0].specification && !old[0].quantity ? [{ ...old[0], species }] : [...old, { ...emptyLine(nextLine.current++), species }]);
      setNotice(`${species}를 견적 품목에 담았어요.`);
    }
    setSuccess(null); document.getElementById("quote")?.scrollIntoView({ behavior: "smooth" });
  }
  function prepare(event: FormEvent) {
    event.preventDefault(); setError("");
    if (testMode && !testAcknowledged) { setTestNoticeOpen(true); return; }
    const candidate = { requestId: newRequestId(), customerName: name, customerPhone: phone, region, items: lines.map(({ species, specification, quantity }) => ({ species, specification, quantity: Number(quantity) })), message: testMode ? testRequestMessage(message) : message, processingBasis: "quote_request", website, customerEmail: email, formalQuoteRequested: formalQuote, recipientName: formalQuote ? recipient : "", replyChannel: formalQuote ? replyChannel : "sms" };
    const parsed = quoteSchema.safeParse(candidate);
    if (!parsed.success) { setError(parsed.error.issues[0]?.message ?? "입력 내용을 확인해주세요."); return; }
    const { requestId: _, ...payload } = parsed.data;
    const fingerprint = requestFingerprint(payload);
    if (attempt.current?.fingerprint !== fingerprint) attempt.current = { fingerprint, id: candidate.requestId };
    setReview({ ...parsed.data, requestId: attempt.current.id });
  }
  async function submit() {
    if (!review || inFlight.current) return;
    if (testMode && !testAcknowledged) { setTestNoticeOpen(true); return; }
    inFlight.current = true;
    setBusy(true); setError(""); setUncertain(true);
    try {
      sessionStorage.setItem("namufarm.pending-request", JSON.stringify({ request: review, createdAt: Date.now() }));
    } catch {
      // Do not submit unless a retry token can survive a reload in this tab.
      setError("このブラウザでは接수 정보를 임시 보관할 수 없어요. 일반 브라우저 창에서 다시 열어주세요.".replace("このブラウザでは", "이 브라우저에서는 "));
      setUncertain(false); setBusy(false); inFlight.current = false; return;
    }
    try {
      const response = await fetch("/api/quote-requests", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(review), signal: AbortSignal.timeout(120000) });
      const result = await response.json() as { requestId?: string; error?: string };
      const receipt = validatedQuoteReceipt(result, review.requestId);
      if ([400, 403, 413, 415, 429].includes(response.status)) {
        sessionStorage.removeItem("namufarm.pending-request"); setUncertain(false);
      }
      if (!response.ok || !receipt) throw new Error(result.error || "접수 결과를 확인하지 못했어요. 같은 내용으로 다시 보내주세요.");
      sessionStorage.removeItem("namufarm.pending-request");
      setUncertain(false); setSuccess(receipt); setReview(null); attempt.current = null;
      setName(""); setPhone(""); setRegion(""); setMessage(""); setEmail(""); setFormalQuote(false); setRecipient(""); setReplyChannel("sms"); setLines([emptyLine(nextLine.current++)]);
    } catch (e) { setError(e instanceof Error && e.name !== "TimeoutError" ? e.message : "접수 결과를 확인하지 못했어요. 같은 내용으로 다시 보내주세요."); }
    finally { setBusy(false); inFlight.current = false; }
  }
  const count = lines.filter(line => line.species.trim()).length;
  const total = lines.reduce((n, line) => n + (Number.isSafeInteger(Number(line.quantity)) && Number(line.quantity) > 0 ? Number(line.quantity) : 0), 0);

  return <>
    <a className="skip-link" href="#quote">견적 요청으로 바로 가기</a>
    <header className="site-header"><div className="header-inner">
      <a href="#home" className="brand" aria-label="나무팜 홈"><Sprout strokeWidth={2.3} /><span>나무팜<small>NAMUFARM</small></span></a>
      <nav className="desktop-nav" aria-label="주 메뉴"><a href="#trees">묘목 둘러보기</a><a href="#guide">이용 안내</a><a href="#questions">자주 묻는 질문</a></nav>
      <Button asChild className="header-quote"><a href="#quote"><FileText />견적 요청</a></Button>
      <Dialog open={menuOpen} onOpenChange={setMenuOpen}><DialogTrigger asChild><Button variant="ghost" className="menu-button" aria-label="메뉴 열기"><Menu /></Button></DialogTrigger><DialogContent className="menu-dialog"><DialogHeader><DialogTitle>나무팜</DialogTitle><DialogDescription>원하는 나무를 만나는 첫걸음</DialogDescription></DialogHeader><nav aria-label="모바일 메뉴">{[["trees", "묘목 둘러보기"], ["quote", "견적 요청"], ["guide", "이용 안내"], ["questions", "자주 묻는 질문"]].map(([id, title]) => <a key={id} href={`#${id}`} onClick={() => setMenuOpen(false)}>{title}</a>)}</nav></DialogContent></Dialog>
    </div></header>

    <main id="home">
      <section className="hero" aria-labelledby="hero-title">
        <img src="/nursery-hero.webp" alt="햇살 아래 흙에 뿌리내린 어린 묘목의 이미지" width="1672" height="940" fetchPriority="high" className="hero-image" /><div className="hero-shade" />
        <div className="hero-inner"><span className="eyebrow"><span />당신의 내일을 심는 곳, 나무팜</span><h1 id="hero-title">심고 싶은 나무,<br />견적부터 <em>편하게.</em></h1><p>원하는 묘목과 수량만 알려주세요.<br />규격부터 배송까지, 함께 확인해드릴게요.</p><Button asChild className="hero-cta"><a href="#quote"><FileText />묘목 견적 요청하기</a></Button><span className="hero-note">결제 없이, 필요한 나무부터 상담하세요.</span></div><span className="hero-caption">GROW A GREENER TOMORROW</span>
      </section>
      <div className="benefits wrap" aria-label="견적 상담 안내"><div><FileText /><span><strong>필요한 만큼, 맞춤 견적</strong><small>한 품목부터 여러 품목까지</small></span></div><div><MessageCircle /><span><strong>규격이 어려워도 괜찮아요</strong><small>용도를 남기면 함께 상담해요</small></span></div><div><MapPin /><span><strong>배송까지 한 번에 확인</strong><small>지역에 맞춰 배송 방법 안내</small></span></div></div>

      <SpeciesBrowser selectedNames={lines.map(line => line.species)} onSelect={selectSpecies} />

      <section id="quote" className="quote-section" aria-labelledby="quote-title"><div className="quote-layout wrap">
        <aside className="quote-intro"><span className="overline">LET’S GROW TOGETHER</span><h2 id="quote-title">좋은 시작은,<br />작은 문의에서.</h2><p>정확한 규격을 몰라도 괜찮아요.<br />심을 곳과 필요한 수량을 알려주세요.</p><div className="intro-promise"><ShieldCheck /><span>견적 요청만으로<br /><strong>주문이나 결제가 확정되지 않아요.</strong></span></div><div className="quote-progress"><span>01 <b>품목 작성</b></span><i /><span>02 <b>연락처 입력</b></span><i /><span>03 <b>상담</b></span></div></aside>
        <form className="quote-form" ref={formRef} onSubmit={prepare}>
          {success ? <div className="success-panel" role="status"><CircleCheck /><span className="overline">{testMode ? "TEST REQUEST SAVED" : "REQUEST RECEIVED"}</span><h3>{testMode ? "테스트 요청을 저장했어요." : "견적 요청을 접수했어요."}</h3><p>{testMode ? "테스트 요청만 저장됐습니다. 실제 상담·견적서 발송은 진행하지 않습니다." : <>남겨주신 품목을 확인한 후<br />입력하신 연락처로 안내드릴게요.</>}</p><div className="receipt"><span>견적접수번호</span><code>{success.receiptNumber}</code><span className="receipt-date-label">접수일시 · 한국시간</span><time dateTime={success.createdAt}>{receiptTimeSeoul(success.createdAt)}</time></div><Button type="button" variant="outline" onClick={() => { setSuccess(null); setNotice(""); }}>{testMode ? "다른 테스트 요청하기" : "다른 견적 요청하기"}</Button></div> : <>
            <div className="form-title"><div><FileText /><h3>묘목 견적 요청</h3></div><span>필수 항목 *</span></div>
            {testMode && <div className="test-mode-notice"><strong>{testNotice}</strong><p>연결 확인용 테스트 요청만 저장합니다. 실제 고객정보 대신 테스트용 정보를 입력해주세요.</p><Button type="button" variant="link" onClick={() => setTestNoticeOpen(true)}>테스트 안내 다시 보기</Button></div>}
            {ready === false && <div className="service-notice">온라인 접수를 준비하고 있어요.<br /><span>지금은 양식을 미리 살펴볼 수 있으며, 요청은 전송되지 않습니다.</span></div>}
            <div className="live-notice" role="status">{notice}</div>
            <fieldset className="quote-section-box"><legend><span>01</span>주문 묘목</legend><div className="line-list">{lines.map((line, index) => <div className="item-line" key={line.id}>
              <div className="item-number">품목 {String(index + 1).padStart(2, "0")}<Button type="button" variant="ghost" size="icon" aria-label={`품목 ${index + 1} 삭제`} disabled={lines.length === 1} onClick={() => setLines(old => old.filter(value => value.id !== line.id))}><Trash2 /></Button></div>
              <div className="item-fields"><div className="field species-field"><Label htmlFor={`species-${line.id}`}>묘목 이름 *</Label><Input id={`species-${line.id}`} required maxLength={80} placeholder="예: 감나무, 대추나무" value={line.species} onChange={e => updateLine(line.id, "species", e.target.value)} /></div><div className="field quantity-field"><Label htmlFor={`quantity-${line.id}`}>수량 *</Label><div className="quantity-input"><Input id={`quantity-${line.id}`} required inputMode="numeric" type="number" min="1" max="1000000" step="1" placeholder="100" value={line.quantity} onChange={e => updateLine(line.id, "quantity", e.target.value)} /><span>주</span></div></div><div className="field spec-field"><Label htmlFor={`spec-${line.id}`}>희망 규격 <small>선택</small></Label><Input id={`spec-${line.id}`} maxLength={120} placeholder="예: 2년생, 키 1m · 모르면 비워두세요" value={line.specification} onChange={e => updateLine(line.id, "specification", e.target.value)} /></div></div>
            </div>)}</div><Button className="add-line" type="button" variant="outline" disabled={lines.length >= 20} onClick={() => { setLines(old => [...old, emptyLine(nextLine.current++)]); setNotice(""); }}><Plus />다른 묘목 추가하기</Button></fieldset>
            <fieldset className="quote-section-box"><legend><span>02</span>받는 사람 · 연락처</legend><div className="contact-grid">
              <div className="field"><Label htmlFor="name">성함 *</Label><Input id="name" autoComplete="name" required minLength={2} maxLength={50} placeholder="받으실 분 성함" value={name} onChange={e => setName(e.target.value)} /></div>
              <div className="field"><Label htmlFor="phone">연락처 *</Label><Input id="phone" type="tel" autoComplete="tel" inputMode="tel" required maxLength={20} placeholder="010-1234-5678" value={phone} onChange={e => setPhone(e.target.value)} /></div>
              <div className="field full"><Label htmlFor="email">이메일 <small>{formalQuote && replyChannel !== "sms" ? "견적서 이메일 수신 시 필수" : "선택"}</small></Label><Input id="email" type="email" autoComplete="email" maxLength={254} required={formalQuote && replyChannel !== "sms"} placeholder="견적서를 이메일로 받고 싶다면 입력해주세요" value={email} onChange={e => setEmail(e.target.value)} /></div>
              <div className="field full"><Label htmlFor="region">배송 지역 *</Label><Input id="region" autoComplete="address-level2" required minLength={2} maxLength={100} placeholder="예: 경북 경산시 · 상세 주소는 필요 없어요" value={region} onChange={e => setRegion(e.target.value)} /></div>
              <div className="field full"><Label htmlFor="message">궁금한 점이나 요청사항 <small>선택</small></Label><Textarea id="message" maxLength={testMode ? 900 : 1000} placeholder="심을 장소, 희망 배송 시기 등을 편하게 남겨주세요." value={message} onChange={e => setMessage(e.target.value)} rows={3} /></div>
            </div></fieldset>
            <fieldset className="quote-section-box formal-quote-options"><legend><span>03</span>공식 견적서</legend>
              <div className="formal-quote-heading"><label className="quote-option-check"><input type="checkbox" checked={formalQuote} onChange={e => setFormalQuote(e.target.checked)} /><span>공식 견적서를 요청합니다 <small>선택</small></span></label><button type="button" className="sample-help-button" aria-label="공식 견적서 SAMPLE 양식 보기" title="공식 견적서 견본 보기" onClick={() => setSampleOpen(true)}>?</button></div>
              {formalQuote && <div className="contact-grid">
                <div className="field full"><Label htmlFor="recipient">견적서 수신인·회사명 <small>선택</small></Label><Input id="recipient" maxLength={100} placeholder="비워두면 입력하신 성함으로 작성합니다" value={recipient} onChange={e => setRecipient(e.target.value)} /></div>
                <div className="field full"><Label htmlFor="reply-channel">견적서 받는 방법</Label><select id="reply-channel" value={replyChannel} onChange={e => setReplyChannel(e.target.value as "sms" | "email" | "both")}><option value="sms">문자 · 견적서 이미지</option><option value="email">이메일 · PDF 견적서</option><option value="both">이메일과 문자 · 둘 다</option></select></div>
                <p className="field full submit-note">품목·가격과 배송 조건을 확인한 후 견적서를 작성해 안내드립니다.</p>
              </div>}
            </fieldset>
            <div className="honeypot" aria-hidden="true"><label htmlFor="website">Website</label><input id="website" tabIndex={-1} autoComplete="off" value={website} onChange={e => setWebsite(e.target.value)} /></div>
            <div className="processing-notice"><p>{quoteProcessingNotice}</p><a href="/privacy">개인정보 처리방침</a></div>
            <div ref={errorRef} tabIndex={-1} className={error ? "form-error" : ""} role={error ? "alert" : undefined}>{error}</div>
            <div className="form-summary"><span>요청 품목 <strong>{count}종</strong></span><span>총 수량 <strong>{total.toLocaleString()}주</strong></span></div>
            <Button className="submit-button" type="submit" disabled={busy}><FileText />견적 요청 내용 확인</Button><p className="submit-note">상담 후 규격, 금액과 배송 방법을 함께 확인해요.</p>
          </>}
        </form>
      </div></section>

      <section id="guide" className="guide-section wrap" aria-labelledby="guide-title"><div className="section-heading"><div><span className="overline">HOW IT WORKS</span><h2 id="guide-title">나무를 만나는 세 단계</h2></div><p>복잡한 가입 없이 시작하세요.</p></div><div className="steps">{[{ icon: Sprout, title: "원하는 묘목 선택", text: "나무 이름과 수량을 적어주세요.\n여러 품목을 함께 요청할 수 있어요." }, { icon: FileText, title: "견적 요청 보내기", text: "연락처와 배송 지역을 남겨주세요.\n접수 완료 화면에서 번호를 안내해요." }, { icon: MessageCircle, title: "상담 후 주문 결정", text: "규격과 재고, 배송비를 안내받고\n충분히 상담한 뒤 결정하세요." }].map((step, i) => <article className="step" key={step.title}><div className="step-top"><step.icon /><span>0{i + 1}</span></div><h3>{step.title}</h3><p>{step.text}</p></article>)}</div></section>
      <section id="questions" className="faq-section wrap"><div><span className="overline">A LITTLE MORE HELP</span><h2>문의 전에<br />궁금하셨나요?</h2></div><Accordion type="single" collapsible className="faq-list">
        <AccordionItem value="size"><AccordionTrigger>묘목 규격을 잘 몰라도 요청할 수 있나요?</AccordionTrigger><AccordionContent>네. 묘목 이름과 필요한 수량만 적어주세요. 심을 장소와 용도를 요청사항에 남겨주시면 상담에 도움이 됩니다.</AccordionContent></AccordionItem>
        <AccordionItem value="order"><AccordionTrigger>견적을 요청하면 바로 주문되나요?</AccordionTrigger><AccordionContent>견적 요청은 상담을 위한 문의입니다. 재고, 규격, 금액과 배송 방법을 확인하고 주문을 결정하시면 됩니다.</AccordionContent></AccordionItem>
        <AccordionItem value="delivery"><AccordionTrigger>배송비와 배송 날짜는 어떻게 정하나요?</AccordionTrigger><AccordionContent>묘목 크기, 수량과 배송 지역에 따라 달라집니다. 접수한 요청을 확인한 후 가능한 배송 방법과 비용, 일정을 안내드립니다.</AccordionContent></AccordionItem>
        <AccordionItem value="more"><AccordionTrigger>목록에 없는 나무도 문의할 수 있나요?</AccordionTrigger><AccordionContent>네. 견적 요청 양식에 원하는 나무 이름을 직접 입력해주세요. 공급 가능 여부는 상담을 통해 확인합니다.</AccordionContent></AccordionItem>
      </Accordion></section>
    </main>
    <footer className="site-footer"><div className="wrap footer-inner"><div><div className="footer-brand-row"><a href="#home" className="brand"><Sprout /><span>나무팜<small>NAMUFARM</small></span></a><div className="footer-business"><span>{businessInfo.name} · 대표 {businessInfo.representative}</span><span>사업자등록번호 {businessInfo.number}</span></div></div><p>당신의 내일에, 푸르름을 더합니다.</p></div><div><a href="#quote">묘목 견적 상담</a><a href="/privacy">개인정보 처리방침</a><small>© {new Date().getFullYear()} NAMUFARM. All rights reserved.</small></div></div></footer>
    <div className={`mobile-cta ${atForm || review ? "is-hidden" : ""}`}><span><strong>어떤 나무를 심을까요?</strong><small>품목과 수량만 알려주세요.</small></span><Button asChild><a href="#quote"><FileText />견적 요청</a></Button></div>
    <Dialog open={!!review} onOpenChange={open => { if (!open && !busy && !uncertain) setReview(null); }}><DialogContent className="review-dialog" onInteractOutside={e => { if (busy || uncertain) e.preventDefault(); }} onEscapeKeyDown={e => { if (busy || uncertain) e.preventDefault(); }} showCloseButton={!busy && !uncertain}><DialogHeader><DialogTitle>{testMode ? "테스트 요청을 보내기 전에 확인해주세요" : "보내기 전에 확인해주세요"}</DialogTitle><DialogDescription>{testMode ? testNotice : "아래 내용으로 묘목 견적 상담을 요청합니다."}</DialogDescription></DialogHeader>{review && <><section className="review-section-box" aria-labelledby="review-items-title"><h3 id="review-items-title">주문 묘목</h3><div className="review-items">{review.items.map((item, i) => <div key={i}><span><strong>{item.species}</strong><small>{item.specification || "규격 상담 희망"}</small></span><b>{item.quantity.toLocaleString()}주</b></div>)}</div></section><section className="review-section-box" aria-labelledby="review-contact-title"><h3 id="review-contact-title">받는 사람 · 연락처</h3><dl className="review-contact"><dt>성함</dt><dd>{review.customerName}</dd><dt>연락처</dt><dd>{review.customerPhone}</dd><dt>배송 지역</dt><dd>{review.region}</dd>{review.customerEmail && <><dt>이메일</dt><dd>{review.customerEmail}</dd></>}{review.formalQuoteRequested && <><dt>공식 견적서</dt><dd>{review.replyChannel === "email" ? "이메일 · PDF" : review.replyChannel === "both" ? "이메일과 문자" : "문자 · 이미지"}</dd><dt>견적서 수신인</dt><dd>{review.recipientName || review.customerName}</dd></>}{review.message && <><dt>요청사항</dt><dd>{review.message}</dd></>}</dl></section>{ready === false && <p className="service-notice">온라인 접수를 준비하고 있어요. 현재 요청은 전송할 수 없습니다.</p>}{uncertain && <p className="service-notice">접수 결과를 확인 중입니다. 같은 요청번호로 다시 보내면 중복 접수되지 않아요. 확인 전에는 내용을 수정할 수 없습니다.</p>}{error && <p className="form-error" role="alert">{error}</p>}<Button className="submit-button" type="button" disabled={busy || ready !== true} onClick={submit}>{busy ? <LoaderCircle className="animate-spin" /> : <CheckCheck />}{busy ? "접수 확인 중…" : testMode ? "테스트 요청 보내기" : "견적 요청 보내기"}</Button><Button type="button" variant="outline" onClick={() => setReview(null)} disabled={busy || uncertain}>내용 수정하기</Button><p className="submit-note">{testMode ? "테스트 요청만 저장되며 실제 견적·주문으로 처리하지 않습니다." : "접수 완료 안내가 나오면 요청이 저장된 상태입니다."}</p></>}</DialogContent></Dialog>
    <Dialog open={testNoticeOpen} onOpenChange={setTestNoticeOpen}><DialogContent className="test-notice-dialog"><DialogHeader><DialogTitle>견적 요청 테스트 안내</DialogTitle><DialogDescription>{testNotice}</DialogDescription></DialogHeader><p>테스트 요청은 연결 확인용으로만 저장하며, 견적 상담·견적서 발송·구매·배송은 진행하지 않습니다.</p><p>실제 고객정보를 입력하지 말고 테스트용 이름과 연락처를 사용해주세요.</p><Button type="button" onClick={() => { setTestAcknowledged(true); setTestNoticeOpen(false); }}>확인 · 테스트 양식 보기</Button></DialogContent></Dialog>
    <Dialog open={sampleOpen} onOpenChange={setSampleOpen}><DialogContent className="quote-sample-dialog"><DialogHeader><DialogTitle>공식 견적서 SAMPLE</DialogTitle><DialogDescription>수신 업체 정보는 공개하지 않은 양식 견본입니다. 품목·수량·금액은 예시이며 실제 견적이 아닙니다.</DialogDescription></DialogHeader><div className="quote-sample-preview"><img src="/quote-sample.webp" alt="나무팜농원 공식 견적서 견본. 블루베리와 감나무 예시 품목, 수신 업체 정보 비공개, 가운데 SAMPLE 워터마크." width="1190" height="1684" /></div><Button asChild variant="outline"><a href="/quote-sample.pdf" target="_blank" rel="noopener noreferrer">SAMPLE PDF 보기</a></Button></DialogContent></Dialog>
  </>;
}
