import { inquirySchema, readInquiryResponse } from "@/lib/inquiry";
import { jsonResult, quoteSettings } from "@/lib/quote-server";
import { privateFingerprint } from "@/lib/privacy-crypto";

export async function GET() {
  const settings = quoteSettings();
  if (!settings.ready) return jsonResult({ ready: false, testMode: settings.testMode });
  try {
    const url = new URL(settings.url); url.searchParams.set("_nf", crypto.randomUUID());
    const response = await fetch(url, { cache: "no-store", redirect: "follow", signal: AbortSignal.timeout(15000) });
    const info = response.ok ? await response.json() as Record<string, unknown> : null;
    return jsonResult({ ready: info?.ok === true && info?.service === "namufarm-quote-requests" && info?.customerInquiry === 1 && info?.customerInquiryReady === true && !settings.testMode, testMode: settings.testMode });
  } catch { return jsonResult({ ready: false, testMode: settings.testMode }); }
}

export async function POST(request: Request) {
  const settings = quoteSettings();
  const reject = (error: string, status: number) => jsonResult({ receiptState: "not_sent", error }, status);
  if (!settings.ready || settings.testMode) return reject("온라인 문의 접수를 준비하고 있어요. 전화로 상담해주세요.", 503);
  if (request.headers.get("origin") !== new URL(request.url).origin) return reject("홈페이지에서 다시 요청해주세요.", 403);
  if (!request.headers.get("content-type")?.startsWith("application/json")) return reject("입력 내용을 확인해주세요.", 415);
  const reader = request.body?.getReader();
  if (!reader) return reject("입력 내용을 확인해주세요.", 400);
  let bytes = 0; const chunks: Uint8Array[] = [];
  while (true) {
    const next = await reader.read(); if (next.done) break;
    bytes += next.value.byteLength;
    if (bytes > 16000) { await reader.cancel(); return reject("문의 내용이 너무 길어요.", 413); }
    chunks.push(next.value);
  }
  let input: unknown;
  try {
    const all = new Uint8Array(bytes); let offset = 0;
    for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
    input = JSON.parse(new TextDecoder().decode(all));
  } catch { return reject("입력 내용을 확인해주세요.", 400); }
  const parsed = inquirySchema.safeParse(input);
  if (!parsed.success) return reject("이름·연락처·문의 내용과 개인정보 동의를 확인해주세요.", 400);
  try {
    const fingerprint = await privateFingerprint(settings.secret, JSON.stringify(parsed.data));
    const rateKey = await privateFingerprint(settings.secret, "inquiry:" + (request.headers.get("cf-connecting-ip") ?? "preview"));
    const url = new URL(settings.url); url.searchParams.set("_nf", crypto.randomUUID());
    const response = await fetch(url, { method: "POST", redirect: "follow", cache: "no-store", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "submit_customer_inquiry", webSecret: settings.secret, protocolVersion: 1, requestId: parsed.data.requestId, fingerprint, rateKey, inquiry: parsed.data }), signal: AbortSignal.timeout(60000) });
    const result = await readInquiryResponse(response, parsed.data.requestId);
    return jsonResult(result.body, result.status);
  } catch { return jsonResult({ receiptState: "unknown", error: "접수 결과를 확인하지 못했어요. 입력 내용은 유지됩니다. 잠시 후 같은 문의로 접수를 확인해주세요." }, 503); }
}
