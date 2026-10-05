export type SellerKey = { keyId: string; publicKey: string };
export type QuoteService = {
  ok: true; service: "namufarm-quote-requests"; version: 4; encryptionReady: true;
  sellerKey: SellerKey; retentionDays: 30; processingBases: string[]; requestFields: string[];
};

// This is operator configuration, never a public form field or a caller-supplied key.
// Apps Script also verifies its own key and privacy readiness before accepting a row.
export async function configuredSellerKey(raw: string | undefined): Promise<SellerKey | null> {
  if (!raw) return null;
  try {
    const key = JSON.parse(raw) as SellerKey;
    if (!key || !/^[a-f0-9]{64}$/.test(key.keyId) || typeof key.publicKey !== "string"
      || !/^[A-Za-z0-9+/]{563}=$/.test(key.publicKey)) return null;
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(key.publicKey));
    const id = Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join("");
    if (id !== key.keyId) return null;
    return { keyId: key.keyId, publicKey: key.publicKey };
  } catch { return null; }
}

export function validatedQuoteService(value: unknown): QuoteService | null {
  if (!value || typeof value !== "object") return null;
  const service = value as QuoteService;
  return service.ok === true && service.service === "namufarm-quote-requests"
    && service.version === 4 && service.encryptionReady === true && service.retentionDays === 30
    && typeof service.sellerKey?.publicKey === "string" && /^[a-f0-9]{64}$/.test(service.sellerKey.keyId)
    && Array.isArray(service.processingBases) && service.processingBases.includes("quote_request")
    && Array.isArray(service.requestFields)
    && ["customerEmail", "formalQuoteRequested", "recipientName", "replyChannel"].every(field => service.requestFields.includes(field))
    ? service : null;
}
