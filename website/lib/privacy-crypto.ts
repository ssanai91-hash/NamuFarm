export type SellerPublicKey = { keyId: string; publicKey: string };
export type PrivacyEnvelope = { version: 1; algorithm: "RSA-OAEP-256+A256GCM"; keyId: string; wrappedKey: string; iv: string; ciphertext: string };
const utf8 = new TextEncoder();
function b64(bytes: Uint8Array): string { return btoa(Array.from(bytes, b => String.fromCharCode(b)).join("")); }
function unb64(value: string): Uint8Array<ArrayBuffer> {
  if (value.length > 2000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(value)) throw new Error("INVALID_PUBLIC_KEY");
  return Uint8Array.from(atob(value), c => c.charCodeAt(0));
}
export async function sha256(value: string): Promise<string> {
  const hash = await crypto.subtle.digest("SHA-256", utf8.encode(value));
  return Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, "0")).join("");
}
export async function privateFingerprint(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", utf8.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  return Array.from(new Uint8Array(await crypto.subtle.sign("HMAC", key, utf8.encode(value))), b => b.toString(16).padStart(2, "0")).join("");
}
/** Encryption happens before Apps Script/Sheets receive any customer plaintext. Private key never leaves the seller app. */
export async function sealCustomer(value: unknown, seller: SellerPublicKey, kind: "quote" | "order", reference: string): Promise<PrivacyEnvelope> {
  if (seller.keyId !== await sha256(seller.publicKey)) throw new Error("INVALID_PUBLIC_KEY");
  const publicKey = await crypto.subtle.importKey("spki", unb64(seller.publicKey), { name: "RSA-OAEP", hash: "SHA-256" }, false, ["encrypt"]);
  if ((publicKey.algorithm as RsaKeyAlgorithm).modulusLength !== 3072) throw new Error("INVALID_PUBLIC_KEY");
  const plain = utf8.encode(JSON.stringify(value));
  if (plain.length < 1 || plain.length > 30000) throw new Error("INVALID_SIZE");
  const key = await crypto.subtle.generateKey({ name: "AES-GCM", length: 256 }, true, ["encrypt"]);
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", key));
  try {
    const ciphertext = await crypto.subtle.encrypt({ name: "AES-GCM", iv, additionalData: utf8.encode(`namufarm:v1:${kind}:${reference}`), tagLength: 128 }, key, plain);
    const wrapped = await crypto.subtle.encrypt({ name: "RSA-OAEP" }, publicKey, raw);
    return { version: 1, algorithm: "RSA-OAEP-256+A256GCM", keyId: seller.keyId, wrappedKey: b64(new Uint8Array(wrapped)), iv: b64(iv), ciphertext: b64(new Uint8Array(ciphertext)) };
  } finally { raw.fill(0); plain.fill(0); }
}
