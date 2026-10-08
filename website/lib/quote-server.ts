import { env } from "cloudflare:workers";
import { validatedQuoteService } from "./quote-service";
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
    const url = new URL(settings.url);
    url.searchParams.set("_nf", crypto.randomUUID());
    const response = await fetch(url, { redirect: "follow", cache: "no-store", signal: AbortSignal.timeout(45000) });
    return response.ok ? validatedQuoteService(await response.json()) : null;
  } catch { return null; }
}
export async function quoteServiceReady(settings: ReturnType<typeof quoteSettings>): Promise<boolean> { return !!(await quoteServiceInfo(settings)); }
export function jsonResult(value: unknown, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
