import { jsonResult, quoteSettings } from "@/lib/quote-server";
import { quoteSchema } from "@/lib/quote-schema";
import { sealCustomer, privateFingerprint } from "@/lib/privacy-crypto";
/** Owner migration bridge. Never logs, writes or returns the received plaintext. */
export async function POST(request: Request) {
  const settings = quoteSettings();
  const authorization = request.headers.get("authorization") ?? "";
  const expected = `Bearer ${settings.secret}`;
  let difference = authorization.length ^ expected.length;
  for (let i = 0; i < expected.length; i++) difference |= (authorization.charCodeAt(i) || 0) ^ expected.charCodeAt(i);
  if (settings.secret.length < 32 || difference !== 0) return jsonResult({ ok: false }, 401);
  if (Number(request.headers.get("content-length") || 0) > 50000) return jsonResult({ ok: false }, 413);
  try {
    const reader=request.body?.getReader(); if(!reader) return jsonResult({ok:false},400);
    const chunks: Uint8Array[]=[];let bytes=0;
    while(true) {const next=await reader.read();if(next.done)break;bytes+=next.value.byteLength;if(bytes>30000){await reader.cancel();return jsonResult({ok:false},413)}chunks.push(next.value)}
    const all=new Uint8Array(bytes);let offset=0;for(const chunk of chunks){all.set(chunk,offset);offset+=chunk.length}
    const text=new TextDecoder().decode(all);
    const body = JSON.parse(text);
    const parsed = quoteSchema.safeParse({ ...body.request, website: "" });
    if (!parsed.success) return jsonResult({ ok: false }, 400);
    // Key is pinned to the backend's live public-key configuration, not chosen by the caller.
    const response = await fetch(settings.url, { cache: "no-store", signal: AbortSignal.timeout(5000) });
    const service = await response.json() as { sellerKey?: { keyId: string; publicKey: string } };
    if (!service.sellerKey) return jsonResult({ ok: false }, 503);
    return jsonResult({ ok: true, encryptedRequest: await sealCustomer(parsed.data, service.sellerKey, "quote", parsed.data.requestId),
      fingerprint: await privateFingerprint(settings.secret, JSON.stringify(parsed.data)) });
  } catch { return jsonResult({ ok: false }, 400); }
}
