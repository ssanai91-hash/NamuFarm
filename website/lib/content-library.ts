import bundledVideos from "./video-catalog.json" with { type: "json" };
import bundledShorts from "./short-video-catalog.json" with { type: "json" };

export type VideoFormat = "video" | "short";

export type VideoResource = {
  id: string;
  kind: "youtube";
  format: VideoFormat;
  title: string;
  videoId: string;
  url: string;
  species: string[];
  topics: string[];
  source: string;
  order: number;
};

// Verified regular-video and Shorts snapshots. Keep the owner's first reference
// pinned. A successful Sheets response still controls the live catalog,
// including removals and an intentionally empty list; failures retain this snapshot.
export const initialVideos: VideoResource[] = readVideoCatalog({
  ok: true, catalogVersion: 1, items: [...bundledVideos, ...bundledShorts],
}) ?? [];

export function youtubeVideoId(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 2048) return null;
  try {
    const url = new URL(value);
    if (!["https:", "http:"].includes(url.protocol) || url.username || url.password || url.port) return null;
    const host = url.hostname.toLowerCase();
    let id: string | null = null;
    if (["youtu.be", "www.youtu.be"].includes(host)) {
      const parts = url.pathname.split("/").filter(Boolean);
      if (parts.length === 1) id = parts[0];
    } else if (["youtube.com", "www.youtube.com", "m.youtube.com", "www.youtube-nocookie.com", "youtube-nocookie.com"].includes(host)) {
      if (url.pathname === "/watch") id = url.searchParams.get("v");
      else {
        const match = url.pathname.match(/^\/(?:embed|shorts|live)\/([A-Za-z0-9_-]{11})\/?$/);
        id = match?.[1] ?? null;
      }
    }
    return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
  } catch { return null; }
}

function shortText(value: unknown, max: number): string {
  return typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max) : "";
}
function tags(value: unknown): string[] {
  const values = Array.isArray(value) ? value : typeof value === "string" ? value.split(/[,，]/) : [];
  return [...new Set(values.map(v => shortText(v, 40)).filter(Boolean))].slice(0, 12);
}

// Whitelist public fields; never forward an upstream object/customer data to visitors.
export function readVideoCatalog(value: unknown): VideoResource[] | null {
  if (!value || typeof value !== "object") return null;
  const body = value as Record<string, unknown>;
  if (body.ok !== true || body.catalogVersion !== 1 || !Array.isArray(body.items) || body.items.length > 1000) return null;
  const videos: VideoResource[] = [];
  const seen = new Set<string>();
  const seenVideos = new Set<string>();
  for (const raw of body.items) {
    if (!raw || typeof raw !== "object") continue;
    const item = raw as Record<string, unknown>;
    if (item.kind !== "youtube") continue; // Blog records are reserved for a later release.
    const id = shortText(item.id, 80), title = shortText(item.title, 200);
    const videoId = youtubeVideoId(item.url);
    if (!/^[A-Za-z0-9_-]+$/.test(id) || !title || !videoId || seen.has(id) || seenVideos.has(videoId)) continue;
    seen.add(id);
    seenVideos.add(videoId);
    // Legacy Apps Script normalizes URLs to /watch but preserves the content ID.
    // shorts-<videoId> therefore keeps the format without changing the live script.
    const format: VideoFormat = item.format === "short" || id === `shorts-${videoId}` ||
      new URL(item.url as string).pathname.startsWith("/shorts/") ? "short" : "video";
    videos.push({ id, kind: "youtube", format, title, videoId,
      url: format === "short" ? `https://www.youtube.com/shorts/${videoId}` : `https://www.youtube.com/watch?v=${videoId}`,
      species: tags(item.species), topics: tags(item.topics), source: shortText(item.source, 100),
      order: typeof item.order === "number" && Number.isFinite(item.order) ? item.order : 9999 });
  }
  return videos.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title, "ko"));
}

export function filterVideos(items: VideoResource[], query: string, species: string, topic: string, format?: VideoFormat) {
  const needle = query.trim().normalize("NFKC").toLocaleLowerCase("ko").replace(/\s+/g, "");
  return items.filter(item => (!format || item.format === format) && (species === "전체" || item.species.includes(species)) &&
    (topic === "전체" || item.topics.includes(topic)) &&
    (!needle || [item.title, item.source, ...item.species, ...item.topics].join(" ").normalize("NFKC").toLocaleLowerCase("ko").replace(/\s+/g, "").includes(needle)));
}

export function youtubeEmbedUrl(videoId: string) {
  if (!/^[A-Za-z0-9_-]{11}$/.test(videoId)) throw new Error("Invalid video id");
  return `https://www.youtube-nocookie.com/embed/${videoId}?playsinline=1&rel=0`;
}
