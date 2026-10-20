import { env } from "cloudflare:workers";
import { readVideoCatalog } from "@/lib/content-library";

export async function GET() {
  const script = (env as unknown as Record<string, string | undefined>).QUOTE_SCRIPT_URL ?? "";
  if (!/^https:\/\/script\.google\.com\/macros\/s\/[A-Za-z0-9_-]+\/exec$/.test(script)) return unavailable();
  try {
    const url = new URL(script);
    url.searchParams.set("action", "content_library");
    url.searchParams.set("_nf", crypto.randomUUID());
    const response = await fetch(url, { redirect: "follow", cache: "no-store", signal: AbortSignal.timeout(15000) });
    if (!response.ok || !response.headers.get("content-type")?.includes("application/json")) return unavailable();
    // Bound the response before parsing, including upstream chunked responses.
    const reader = response.body?.getReader();
    if (!reader) return unavailable();
    const chunks: Uint8Array[] = []; let bytes = 0;
    while (true) {
      const next = await reader.read(); if (next.done) break;
      bytes += next.value.byteLength;
      if (bytes > 1000000) { await reader.cancel(); return unavailable(); }
      chunks.push(next.value);
    }
    const all = new Uint8Array(bytes); let offset = 0;
    for (const chunk of chunks) { all.set(chunk, offset); offset += chunk.length; }
    const videos = readVideoCatalog(JSON.parse(new TextDecoder().decode(all)));
    if (videos === null) return unavailable();
    return Response.json({ videos, syncedAt: new Date().toISOString() }, { headers: {
      "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff",
    } });
  } catch { return unavailable(); }
}

function unavailable() {
  return Response.json({ error: "영상 목록을 갱신하지 못했어요. 잠시 후 다시 시도해주세요." }, {
    status: 503, headers: { "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" },
  });
}
