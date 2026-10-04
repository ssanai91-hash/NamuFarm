import { jsonResult, quoteSettings } from "@/lib/quote-server";
export function GET() { const settings = quoteSettings(); return jsonResult({ ready: settings.ready, privacyNotice: settings.privacy }); }
