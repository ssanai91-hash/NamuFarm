import { jsonResult, quoteSettings } from "@/lib/quote-server";
// Opening the form must not depend on a second, potentially slow Apps Script call.
// The authenticated POST endpoint validates and commits the request in one round trip.
export async function GET() { const settings = quoteSettings(); return jsonResult({ ready: settings.ready, privacyNotice: settings.privacy, testMode: settings.testMode }); }
