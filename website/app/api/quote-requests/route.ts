import { quoteSchema } from "@/lib/quote-schema";
import { jsonResult, quoteSettings } from "@/lib/quote-server";
import { privateFingerprint } from "@/lib/privacy-crypto";
import { readQuoteResponse } from "@/lib/quote-upstream";
export async function POST(request: Request) {
  const settings = quoteSettings();
  if (!settings.ready) return jsonResult({ receiptState: "not_sent", error: "온라인 접수를 준비하고 있습니다. 아직 요청이 전송되지 않았어요." }, 503);
  if (request.headers.get("origin") !== new URL(request.url).origin) return jsonResult({ receiptState: "not_sent", error: "홈페이지에서 다시 요청해주세요." }, 403);
  if (!request.headers.get("content-type")?.startsWith("application/json")) return jsonResult({ receiptState: "not_sent", error: "잘못된 요청입니다." }, 415);
  const reader = request.body?.getReader();
  if (!reader) return jsonResult({ receiptState: "not_sent", error: "입력 내용을 확인해주세요." }, 400);
  let bytes = 0; const chunks: Uint8Array[] = [];
  while (true) {
    const next = await reader.read(); if (next.done) break;
    bytes += next.value.byteLength;
    if (bytes > 24000) { await reader.cancel(); return jsonResult({ receiptState: "not_sent", error: "입력 내용이 너무 깁니다." }, 413); }
    chunks.push(next.value);
  }
  let input: unknown;
  try {
    const all = new Uint8Array(bytes); let offset = 0;
    for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
    input = JSON.parse(new TextDecoder().decode(all));
  } catch { return jsonResult({ receiptState: "not_sent", error: "입력 내용을 확인해주세요." }, 400); }
  const parsed = quoteSchema.safeParse(input);
  if (!parsed.success) return jsonResult({ receiptState: "not_sent", error: "품목, 수량과 연락처를 다시 확인해주세요." }, 400);
  // Older Apps Script deployments require nonempty location text; normalize before fingerprinting.
  if (!parsed.data.region) parsed.data.region = "미입력";
  const ip = request.headers.get("cf-connecting-ip") ?? "preview";
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(settings.secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(ip));
  const rateKey = Array.from(new Uint8Array(signature), b => b.toString(16).padStart(2, "0")).join("");
  const started = Date.now();
  try {
    const fingerprint = await privateFingerprint(settings.secret, JSON.stringify(parsed.data));
    const url = new URL(settings.url);
    // ContentService redirect URLs are short-lived; never reuse a cached redirect.
    url.searchParams.set("_nf", crypto.randomUUID());
    const response = await fetch(url, { method: "POST", redirect: "follow", cache: "no-store", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "submit_request", webSecret: settings.secret, rateKey, requestId: parsed.data.requestId, fingerprint, protocolVersion: 5, request: parsed.data }), signal: AbortSignal.timeout(60000) });
    const result = await readQuoteResponse(response, parsed.data.requestId);
    console.info("quote_submission", JSON.stringify({ status: result.status, code: result.diagnostic ?? "OK", elapsedMs: Date.now() - started }));
    return jsonResult({ ...result.body, ...(result.status === 201 ? { testMode: settings.testMode } : {}) }, result.status);
  } catch (error) {
    const code = error instanceof Error && error.name === "TimeoutError" ? "UPSTREAM_TIMEOUT" : "UPSTREAM_NETWORK";
    console.warn("quote_submission", JSON.stringify({ code, elapsedMs: Date.now() - started }));
    return jsonResult({ code, receiptState: "unknown", error: "접수 결과를 확인하지 못했어요. 창을 닫아도 입력 내용은 유지됩니다. 잠시 후 같은 요청으로 확인해주세요." }, 503);
  }
}
