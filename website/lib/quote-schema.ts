import { z } from "zod";
export const quoteItemSchema = z.object({
  species: z.string().regex(/^[^\x00-\x08\x0b\x0c\x0e-\x1f]*$/, "입력할 수 없는 문자가 있습니다.").trim().min(1, "묘목 이름을 입력해주세요.").max(80),
  specification: z.string().regex(/^[^\x00-\x08\x0b\x0c\x0e-\x1f]*$/, "입력할 수 없는 문자가 있습니다.").trim().max(120),
  quantity: z.number().int().min(1, "수량은 1주 이상 입력해주세요.").max(1000000),
}).strict();
export const quoteSchema = z.object({
  requestId: z.string().regex(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/),
  customerName: z.string().regex(/^[^\x00-\x08\x0b\x0c\x0e-\x1f]*$/, "입력할 수 없는 문자가 있습니다.").trim().min(2, "성함을 2자 이상 입력해주세요.").max(50),
  customerPhone: z.string().transform(v => v.replace(/[\s()-]/g, "")).pipe(z.string().regex(/^0\d{8,10}$/, "연락 가능한 전화번호를 확인해주세요.")),
  region: z.string().regex(/^[^\x00-\x08\x0b\x0c\x0e-\x1f]*$/, "입력할 수 없는 문자가 있습니다.").trim().min(2, "배송받을 시·군·구를 입력해주세요.").max(100),
  items: z.array(quoteItemSchema).min(1).max(20),
  message: z.string().regex(/^[^\x00-\x08\x0b\x0c\x0e-\x1f]*$/, "입력할 수 없는 문자가 있습니다.").trim().max(1000),
  consent: z.literal(true, { errorMap: () => ({ message: "개인정보 수집·이용에 동의해주세요." }) }),
  website: z.string().max(0),
}).strict();
export type QuoteItem = z.infer<typeof quoteItemSchema>;
export type QuoteRequest = z.infer<typeof quoteSchema>;
export function requestFingerprint(value: Omit<QuoteRequest, "requestId">) { return JSON.stringify(value); }

// getRandomValues is also available in the internal HTTP preview; no weak-random fallback.
export function newRequestId(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0,8)}-${hex.slice(8,12)}-${hex.slice(12,16)}-${hex.slice(16,20)}-${hex.slice(20)}`;
}
