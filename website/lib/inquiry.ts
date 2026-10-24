import { z } from "zod";

export const inquiryCategories = ["묘목·재배", "구매·배송", "기타"] as const;
const plainText = z.string().regex(/^[^\x00-\x08\x0b\x0c\x0e-\x1f]*$/, "입력할 수 없는 문자가 있어요.").trim();
export const inquirySchema = z.object({
  requestId: z.string().regex(/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/),
  category: z.enum(inquiryCategories),
  customerName: plainText.min(2, "이름을 2자 이상 입력해주세요.").max(50, "이름은 50자까지 입력해주세요."),
  customerPhone: z.string().transform(v => v.replace(/[\s()-]/g, "")).pipe(z.string().regex(/^0\d{8,10}$/, "연락 가능한 전화번호를 확인해주세요.")),
  customerEmail: z.string().trim().max(254).refine(v => !v || /^[^\s@,;<>]+@[^\s@,;<>]+\.[^\s@,;<>]+$/.test(v), "이메일 주소를 확인해주세요."),
  message: plainText.min(2, "궁금한 내용을 2자 이상 입력해주세요.").max(2000, "문의 내용은 2,000자까지 입력해주세요."),
  consent: z.literal(true, { errorMap: () => ({ message: "문의 답변을 위한 개인정보 수집·이용에 동의해주세요." }) }),
  website: z.string().max(0),
}).strict();
export type InquiryRequest = z.infer<typeof inquirySchema>;
export type InquiryReceipt = { requestId: string; inquiryNumber: string; createdAt: string; kind: "customer_inquiry" };
export const inquiryPrivacyNotice = "문의 답변과 이력 관리에 이름·전화번호·문의 내용 및 선택 입력한 이메일을 사용합니다. 미답변 문의는 처리 완료까지, 답변 완료 문의는 완료 후 30일 동안 보관한 뒤 개인정보를 삭제합니다. 동의를 거부할 수 있으나 온라인 문의 접수는 어렵습니다. 전화 상담을 이용해주세요.";
export function validatedInquiryReceipt(value: unknown, expectedId: string): InquiryReceipt | null {
  if (!value || typeof value !== "object") return null;
  const r = value as InquiryReceipt;
  if (r.kind !== "customer_inquiry" || r.requestId !== expectedId || typeof r.createdAt !== "string" || typeof r.inquiryNumber !== "string") return null;
  const time = Date.parse(r.createdAt);
  if (!Number.isFinite(time) || new Date(time).toISOString() !== r.createdAt) return null;
  const number = /^INQ-(\d{8})-(\d{4,16})$/.exec(r.inquiryNumber);
  if (!number || !Number.isSafeInteger(Number(number[2])) || Number(number[2]) < 1 || number[1] !== new Date(time + 9 * 3600000).toISOString().slice(0, 10).replace(/-/g, "")) return null;
  return { kind: r.kind, requestId: r.requestId, inquiryNumber: r.inquiryNumber, createdAt: r.createdAt };
}

// Only an identity-checked durable receipt can turn a pending attempt into success.
export async function readInquiryResponse(response: Response, requestId: string) {
  const unknown = { receiptState: "unknown", error: "접수 결과를 확인하지 못했어요. 입력 내용은 유지됩니다. 잠시 후 같은 문의로 접수를 확인해주세요." };
  if (!response.ok) { await response.body?.cancel(); return { status: 503, body: unknown }; }
  let value: { ok?: boolean; code?: string } | null;
  try { value = await response.json(); } catch { return { status: 502, body: unknown }; }
  const receipt = validatedInquiryReceipt(value, requestId);
  if (value?.ok === true && receipt) return { status: 201, body: { ...receipt, receiptState: "received" } };
  // NOT_READY can also mean read-back verification failed after a committed write.
  // Keep its retry identity rather than assuming that nothing was saved.
  if (["INVALID_ACTION", "UPGRADE_REQUIRED", "UNAUTHORIZED", "INVALID"].includes(value?.code ?? "")) return { status: 503, body: { receiptState: "not_sent", error: "온라인 문의 접수를 준비하고 있어요. 입력 내용은 유지됩니다. 전화로 상담해주세요." } };
  if (value?.code === "RATE_LIMITED") return { status: 429, body: { receiptState: "not_sent", error: "문의가 많아 잠시 쉬고 있어요. 잠시 후 다시 보내주세요." } };
  if (value?.code === "CONFLICT") return { status: 409, body: { ...unknown, error: "저장된 문의와 내용이 달라요. 전화로 접수 여부를 확인해주세요." } };
  return { status: 503, body: unknown };
}
