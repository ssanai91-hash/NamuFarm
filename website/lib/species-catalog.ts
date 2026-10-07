import speciesData from "./species-data.json";

export const catalogInitials = ["ㄱ", "ㄴ", "ㄷ", "ㄹ", "ㅁ", "ㅂ", "ㅅ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"] as const;
export type CatalogInitial = typeof catalogInitials[number];
export const artworkSlots = {
  persimmon: 0, fruit: 1, deciduous: 2, pine: 3,
  conifer: 4, "pink-blossom": 5, lilac: 6, hydrangea: 7,
  vine: 8, shrub: 9, perennial: 10, grass: 11,
} as const;
export type Artwork = keyof typeof artworkSlots;
export type CatalogSpecies = {
  id: string; name: string; initial: CatalogInitial; farmName: string; artwork: Artwork;
};
export const speciesCatalog = speciesData as CatalogSpecies[];
const hangulInitials = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
const simpleInitials: Record<string, string> = { "ㄲ": "ㄱ", "ㄸ": "ㄷ", "ㅃ": "ㅂ", "ㅆ": "ㅅ", "ㅉ": "ㅈ" };
const normalize = (value: string) => value.normalize("NFC").toLocaleLowerCase("ko").replace(/[\s()·]/g, "");

export function nameInitials(name: string): string {
  return Array.from(name, character => {
    const code = character.charCodeAt(0) - 0xac00;
    const initial = code >= 0 && code < 11172 ? hangulInitials[Math.floor(code / 588)] : character;
    return simpleInitials[initial] ?? initial;
  }).join("");
}

export function filterSpecies(initial: CatalogInitial | "all", query: string): CatalogSpecies[] {
  const needle = normalize(query);
  return speciesCatalog.filter(species => {
    if (initial !== "all" && species.initial !== initial) return false;
    if (!needle) return true;
    return [species.name, species.farmName].some(name => normalize(name).includes(needle) || normalize(nameInitials(name)).includes(needle));
  });
}
