import type { VideoResource } from "./content-library.ts";
import { speciesCatalog } from "./species-catalog.ts";
import { addCatalogLine, emptyLine, type QuoteLine } from "./quote-form.ts";

export type VideoQuoteDraft = { lines: QuoteLine[]; message: string };

// A new video starts independently of the main quote and every other video.
export function createVideoQuoteDraft(video: Pick<VideoResource, "species" | "title" | "url">, lineId: number, maxLength = 1000): VideoQuoteDraft {
  const candidates = videoQuoteSpecies(video);
  return {
    lines: [{ ...emptyLine(lineId), ...(candidates.length === 1 ? { species: candidates[0], quantity: "1" } : {}) }],
    message: appendVideoReference("", video, maxLength),
  };
}

const normalize = (value: string) => value.normalize("NFKC").replace(/\s+/g, "").toLocaleLowerCase("ko");
const catalogNames = new Map(speciesCatalog.map(species => [normalize(species.name), species.name]));

// Resolve only exact catalog names or the same name with a '나무' suffix.
// Do not guess cultivars or map related but different species to each other.
export function videoQuoteSpeciesName(value: string): string {
  const name = value.trim();
  return catalogNames.get(normalize(name)) ?? catalogNames.get(normalize(name.replace(/나무$/, ""))) ?? name;
}

export function videoQuoteSpecies(video: Pick<VideoResource, "species">): string[] {
  return [...new Set(video.species.filter(name => !["", "공통", "기타", "전체"].includes(name.trim()))
    .map(videoQuoteSpeciesName))];
}

export function stageVideoQuote(lines: QuoteLine[], video: Pick<VideoResource, "species">, species: string, newId: number, locked: boolean) {
  if (locked) return { lines, staged: false, added: false, lineId: null, notice: "진행 중인 접수 확인을 먼저 마쳐주세요." };
  if (!videoQuoteSpecies(video).includes(species)) return { lines, staged: false, added: false, lineId: null, notice: "영상에 분류된 묘목을 선택해주세요." };
  const existing = lines.find(line => normalize(videoQuoteSpeciesName(line.species)) === normalize(species));
  if (existing) return { lines, staged: true, added: false, lineId: existing.id, notice: `${existing.species} · 이미 담은 수량과 규격을 유지했어요.` };
  const result = addCatalogLine(lines, species, newId);
  if (!result.added) return { ...result, staged: false, lineId: null };
  const added = result.lines.find(line => line.species === species)!;
  return { ...result, staged: true, lineId: added.id,
    lines: result.lines.map(line => line.id === added.id ? { ...line, quantity: "1" } : line) };
}

export function appendVideoReference(message: string, video: Pick<VideoResource, "title" | "url">, maxLength = 1000): string {
  if (message.includes(video.url)) return message;
  const combined = `${message}${message.trim() ? "\n\n" : ""}[참고 영상] ${video.title}\n${video.url}`;
  // Never truncate or overwrite the customer's own message to fit a reference.
  return combined.length <= maxLength ? combined : message;
}
