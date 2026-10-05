import { env } from "cloudflare:workers";
export function quoteSettings() {
  const values = env as unknown as Record<string, string | undefined>;
  const url = values.QUOTE_SCRIPT_URL ?? "";
  const secret = values.QUOTE_WEB_SECRET ?? "";
  const privacy = values.QUOTE_PRIVACY_NOTICE ?? "";
  const validUrl = /^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(url);
  return { url, secret, privacy, ready: validUrl && secret.length >= 32 && privacy.length >= 30 };
}
export async function quoteServiceReady(settings: ReturnType<typeof quoteSettings>): Promise<boolean> {
  if (!settings.ready) return false;
  try {
    const response = await fetch(settings.url, { redirect: "follow", cache: "no-store", signal: AbortSignal.timeout(5000) });
    const service = await response.json() as { ok?: boolean; service?: string; processingBases?: string[]; requestFields?: string[] };
    return response.ok && service.ok === true && service.service === "namufarm-quote-requests"
      && Array.isArray(service.processingBases) && service.processingBases.includes("quote_request")
      && Array.isArray(service.requestFields)
      && ["customerEmail", "formalQuoteRequested", "recipientName", "replyChannel"].every(field => service.requestFields!.includes(field));
  } catch { return false; }
}
export function jsonResult(value: unknown, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
