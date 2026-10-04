import { env } from "cloudflare:workers";
export function quoteSettings() {
  const values = env as unknown as Record<string, string | undefined>;
  const url = values.QUOTE_SCRIPT_URL ?? "";
  const secret = values.QUOTE_WEB_SECRET ?? "";
  const privacy = values.QUOTE_PRIVACY_NOTICE ?? "";
  const validUrl = /^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(url);
  return { url, secret, privacy, ready: validUrl && secret.length >= 32 && privacy.length >= 30 };
}
export function jsonResult(value: unknown, status = 200) {
  return Response.json(value, { status, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" } });
}
