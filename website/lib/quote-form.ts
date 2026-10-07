import type { ZodIssue } from "zod";
export type QuoteLine = { id: number; species: string; specification: string; quantity: string };
export const emptyLine = (id: number): QuoteLine => ({ id, species: "", specification: "", quantity: "" });
export const isEmptyLine = (line: QuoteLine) => !line.species.trim() && !line.specification.trim() && !line.quantity;
export type FieldProblem = { id: string; message: string };
// UI identifiers never enter the existing strict quote request contract.
export function quoteFieldProblems(issues: ZodIssue[], lines: QuoteLine[]): FieldProblem[] {
  const seen = new Set<string>();
  const order = [...lines.flatMap(line => [`species-${line.id}`, `quantity-${line.id}`, `spec-${line.id}`]), "name", "phone", "region", "message", "email", "recipient", "quote-errors"];
  return issues.map(issue => {
    const [key, index, field] = issue.path;
    if (key === "items" && typeof index === "number" && lines[index]) {
      const prefix = field === "quantity" ? "quantity" : field === "specification" ? "spec" : "species";
      return { id: `${prefix}-${lines[index].id}`, message: field === "quantity" ? `품목 ${index + 1}의 수량을 1~1,000,000주 사이의 정수로 입력해주세요.` : `품목 ${index + 1}: ${issue.message}` };
    }
    const ids: Record<string, string> = { customerName: "name", customerPhone: "phone", region: "region", customerEmail: "email", recipientName: "recipient", message: "message" };
    return { id: ids[String(key)] || "quote-errors", message: issue.message };
  }).filter(problem => { if (seen.has(problem.id)) return false; seen.add(problem.id); return true; })
    .sort((a, b) => order.indexOf(a.id) - order.indexOf(b.id));
}
export function addCatalogLine(lines: QuoteLine[], species: string, newId: number): { lines: QuoteLine[]; added: boolean; notice: string } {
  if (lines.some(line => line.species.trim() === species)) return { lines, added: false, notice: `${species} · 이미 담겨 있어요. 수량은 담은 목록에서 바꿀 수 있어요.` };
  const empty = lines.findIndex(isEmptyLine);
  if (empty >= 0) return { lines: lines.map((line, i) => i === empty ? { ...line, species } : line), added: true, notice: `${species} · 견적에 담았어요. 계속 고르셔도 됩니다.` };
  if (lines.length >= 20) return { lines, added: false, notice: "한 번에 20종까지 요청할 수 있어요. 담은 목록에서 품목을 확인해주세요." };
  return { lines: [...lines, { ...emptyLine(newId), species }], added: true, notice: `${species} · 견적에 담았어요. 계속 고르셔도 됩니다.` };
}
