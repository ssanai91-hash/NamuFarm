import { jsonResult, quoteSettings } from "@/lib/quote-server";
// Opening the form must not depend on a second, potentially slow Apps Script call.
// The POST endpoint verifies the encryption key before sending any customer data.
export async function GET() { const settings = quoteSettings(); return jsonResult({ ready: settings.ready, privacyNotice: settings.privacy, testMode: settings.testMode }); }
