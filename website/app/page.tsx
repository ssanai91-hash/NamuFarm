import QuoteHome from "@/components/quote-home";
import { quoteSettings } from "@/lib/quote-server";
export default function Page() { const settings = quoteSettings(); return <QuoteHome initialTestMode={settings.testMode} initialConfigured={settings.ready} />; }
