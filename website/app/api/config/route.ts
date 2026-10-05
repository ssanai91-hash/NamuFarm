import { jsonResult, quoteSettings, quoteServiceReady } from "@/lib/quote-server";
export async function GET() { const settings = quoteSettings(); return jsonResult({ ready: await quoteServiceReady(settings), privacyNotice: settings.privacy }); }
