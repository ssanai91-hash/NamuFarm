import QuoteHome from "@/components/quote-home";
import { quoteSettings } from "@/lib/quote-server";
export default function Page() { return <QuoteHome initialTestMode={quoteSettings().testMode} />; }
