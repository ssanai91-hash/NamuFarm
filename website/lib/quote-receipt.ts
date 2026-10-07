export type QuoteReceipt = { requestId: string; receiptNumber: string; createdAt: string };

export function validatedQuoteReceipt(value: unknown, expectedId: string): QuoteReceipt | null {
  if (!value || typeof value !== "object") return null;
  const receipt = value as QuoteReceipt;
  if (receipt.requestId !== expectedId || typeof receipt.receiptNumber !== "string"
    || typeof receipt.createdAt !== "string" || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(receipt.createdAt)) return null;
  const time = Date.parse(receipt.createdAt);
  if (!Number.isFinite(time) || new Date(time).toISOString() !== receipt.createdAt) return null;
  const match = /^NFQ-(\d{8})-(\d{4,16})$/.exec(receipt.receiptNumber);
  if (!match || !Number.isSafeInteger(Number(match[2])) || Number(match[2]) < 1
    || match[1] !== new Date(time + 9 * 3600000).toISOString().slice(0, 10).replace(/-/g, "")) return null;
  return { requestId: receipt.requestId, receiptNumber: receipt.receiptNumber, createdAt: receipt.createdAt };
}

export function receiptTimeSeoul(createdAt: string): string {
  return new Date(Date.parse(createdAt) + 9 * 3600000).toISOString().slice(0, 19).replace("T", " ");
}
