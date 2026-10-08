import index from "./species-illustration-index.json";
export type Illustration = { src: string };
// The complete evidence manifest stays out of the client bundle.
const paths = index as Record<string, string>;
export function speciesIllustration(id: string): Illustration | null {
  const src = paths[id];
  return src ? { src } : null;
}
