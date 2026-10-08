import { validatedQuoteReceipt } from "./quote-receipt.ts";

export type SubmissionResult = { status: number; body: Record<string, unknown>; diagnostic?: string };
const unknown = (code: string, message: string, status = 503): SubmissionResult => ({
  status, body: { code, error: message, receiptState: "unknown" }, diagnostic: code,
});

// HTTP failures and HTML error pages are not JSON receipts. Never log upstream
// bodies: successful responses and error pages can contain private information.
export async function readQuoteResponse(response: Response, requestId: string): Promise<SubmissionResult> {
  if (!response.ok) {
    await response.body?.cancel();
    if ([401, 403, 404, 410].includes(response.status)) return unknown(`UPSTREAM_HTTP_${response.status}`,
      "접수 서버에 연결할 수 없어 점검이 필요해요. 잠시 후 같은 요청으로 확인하거나 전화로 문의해주세요.");
    return unknown(`UPSTREAM_HTTP_${response.status}`, "접수 서버가 응답하지 않았어요. 같은 요청으로 다시 확인해주세요.", 502);
  }
  let value: unknown;
  try { value = await response.json(); }
  catch { return unknown("UPSTREAM_INVALID_RESPONSE", "접수 서버에서 올바른 확인 응답을 받지 못했어요. 같은 요청으로 다시 확인해주세요.", 502); }
  const result = value && typeof value === "object" ? value as { ok?: boolean; code?: string } : null;
  const receipt = validatedQuoteReceipt(result, requestId);
  if (result?.ok === true && receipt) return { status: 201, body: { ...receipt } };
  if (result?.code === "CONFLICT") return unknown("CONFLICT", "저장된 요청과 내용이 달라 확인이 필요해요. 새 요청 전에 전화로 접수 여부를 확인해주세요.", 409);
  if (result?.code === "RATE_LIMITED") return unknown("RATE_LIMITED", "요청이 많아 잠시 쉬고 있어요. 잠시 후 같은 요청으로 확인해주세요.", 429);
  if (result?.code === "BUSY") return unknown("UPSTREAM_BUSY", "접수 서버가 다른 요청을 처리하고 있어요. 잠시 후 같은 요청으로 확인해주세요.");
  if (["NOT_READY", "UPGRADE_REQUIRED", "UNAUTHORIZED", "INVALID"].includes(result?.code ?? "")) return unknown(`UPSTREAM_${result?.code}`,
    "접수 서버 설정을 확인해야 해요. 입력 내용은 유지되며 전화로 접수 여부를 확인할 수 있어요.");
  return unknown("UPSTREAM_UNCONFIRMED", "접수 결과를 확인하지 못했어요. 같은 요청으로 다시 확인해주세요.", 502);
}

export function retainPendingReceipt(hadPending: boolean, response: { receiptState?: string }): boolean {
  // A rejection of this HTTP attempt cannot disprove an earlier commit.
  return hadPending || response.receiptState !== "not_sent";
}
