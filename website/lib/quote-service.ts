export type QuoteService = {
  ok: true; service: "namufarm-quote-requests"; version: 5; connectionMode: "device-auth";
  customerDataReady: true; retentionDays: 30; processingBases: string[]; requestFields: string[];
};
export function validatedQuoteService(value: unknown): QuoteService | null {
  if (!value || typeof value !== "object") return null;
  const service = value as QuoteService;
  return service.ok === true && service.service === "namufarm-quote-requests"
    && service.version === 5 && service.connectionMode === "device-auth" && service.customerDataReady === true
    && service.retentionDays === 30
    && Array.isArray(service.processingBases) && service.processingBases.includes("quote_request")
    && Array.isArray(service.requestFields)
    && ["customerEmail", "formalQuoteRequested", "recipientName", "replyChannel"].every(field => service.requestFields.includes(field))
    ? service : null;
}
