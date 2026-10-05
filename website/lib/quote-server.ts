import { env } from "cloudflare:workers";
export function quoteSettings() {
  const values = env as unknown as Record<string, string | undefined>;
  const url = values.QUOTE_SCRIPT_URL ?? "";
  const secret = values.QUOTE_WEB_SECRET ?? "";
  const privacy = values.QUOTE_PRIVACY_NOTICE ?? "";
  const testMode = values.QUOTE_TEST_MODE === "true";
  const validUrl = /^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(url);
  return { url, secret, privacy, testMode, ready: validUrl && secret.length >= 32 && privacy.length >= 30 };
}
export async function quoteServiceInfo(settings: ReturnType<typeof quoteSettings>) {
  if (!settings.ready) return null;
  try {
    const response = await fetch(settings.url, { redirect: "follow", cache: "no-store", signal: AbortSignal.timeout(20000) });
    const service = await response.json() as { ok?: boolean; service?: string; version?: number; encryptionReady?: boolean; sellerKey?: { keyId: string; publicKey: string }; retentionDays?: number; processingBases?: string[]; requestFields?: string[] };
    const ready = response.ok && service.ok === true && service.service === "namufarm-quote-requests"
      && service.version === 4 && service.encryptionReady === true && service.retentionDays === 30
      && typeof service.sellerKey?.publicKey === "string" && /^[a-f0-9]{64}$/.test(service.sellerKey.keyId)
      && Array.isArray(service.processingBases) && service.processingBases.includes("quote_request")
      && Array.isArray(service.requestFields)
      && ["customerEmail", "formalQuoteRequested", "recipientName", "replyChannel"].every(field => service.requestFields!.includes(field));
    return ready ? service : null;
  } catch { return null; }
}
export async function quoteServiceReady(settings: ReturnType<typeof quoteSettings>): Promise<boolean> { return !!(await quoteServiceInfo(settings)); }
export function jsonResult(value: unknown, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
