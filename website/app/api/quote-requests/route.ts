import { quoteSchema } from "@/lib/quote-schema";
import { jsonResult, quoteSettings, quoteServiceInfo } from "@/lib/quote-server";
import { privateFingerprint, sealCustomer } from "@/lib/privacy-crypto";
export async function POST(request: Request) {
  const settings = quoteSettings();
  if (!settings.ready) return jsonResult({ error: "온라인 접수를 준비하고 있습니다. 아직 요청이 전송되지 않았어요." }, 503);
  if (request.headers.get("origin") !== new URL(request.url).origin) return jsonResult({ error: "홈페이지에서 다시 요청해주세요." }, 403);
  if (!request.headers.get("content-type")?.startsWith("application/json")) return jsonResult({ error: "잘못된 요청입니다." }, 415);
  const reader = request.body?.getReader();
  if (!reader) return jsonResult({ error: "입력 내용을 확인해주세요." }, 400);
  let bytes = 0; const chunks: Uint8Array[] = [];
  while (true) {
    const next = await reader.read(); if (next.done) break;
    bytes += next.value.byteLength;
    if (bytes > 24000) { await reader.cancel(); return jsonResult({ error: "입력 내용이 너무 깁니다." }, 413); }
    chunks.push(next.value);
  }
  let input: unknown;
  try {
    const all = new Uint8Array(bytes); let offset = 0;
    for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
    input = JSON.parse(new TextDecoder().decode(all));
  } catch { return jsonResult({ error: "입력 내용을 확인해주세요." }, 400); }
  const parsed = quoteSchema.safeParse(input);
  if (!parsed.success) return jsonResult({ error: "품목, 수량과 연락처를 다시 확인해주세요." }, 400);
  const service = await quoteServiceInfo(settings);
  if (!service?.sellerKey) return jsonResult({ error: "온라인 접수를 준비하고 있습니다. 아직 요청이 전송되지 않았어요." }, 503);
  const ip = request.headers.get("cf-connecting-ip") ?? "preview";
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(settings.secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(ip));
  const rateKey = Array.from(new Uint8Array(signature), b => b.toString(16).padStart(2, "0")).join("");
  try {
    const encryptedRequest = await sealCustomer(parsed.data, service.sellerKey, "quote", parsed.data.requestId);
    const fingerprint = await privateFingerprint(settings.secret, JSON.stringify(parsed.data));
    const response = await fetch(settings.url, { method: "POST", redirect: "follow", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ action: "submit_request", webSecret: settings.secret, rateKey, requestId: parsed.data.requestId, fingerprint, encryptedRequest }), signal: AbortSignal.timeout(22000) });
    const result = await response.json() as { ok?: boolean; requestId?: string; code?: string };
    if (response.ok && result.ok && result.requestId === parsed.data.requestId) return jsonResult({ requestId: result.requestId }, 201);
    if (result.code === "RATE_LIMITED") return jsonResult({ error: "잠시 후 다시 요청해주세요. 입력한 내용은 그대로 유지됩니다." }, 429);
    if (result.code === "CONFLICT") return jsonResult({ error: "같은 요청번호의 내용이 달라 확인이 필요합니다. 새 요청으로 작성해주세요." }, 409);
    return jsonResult({ error: "접수 결과를 확인하지 못했어요. 같은 내용으로 다시 보내면 중복 없이 확인합니다." }, 502);
  } catch { return jsonResult({ error: "연결이 잠시 끊겼어요. 입력 내용은 유지됩니다. 다시 보내기를 눌러 접수 결과를 확인해주세요." }, 503); }
}
