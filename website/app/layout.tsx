import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://namufarm.co.kr"),
  title: "나무팜 | 심고 싶은 나무, 견적부터 편하게",
  description: "원하는 묘목과 수량을 남겨주세요. 규격과 배송 지역에 맞춰 견적 상담을 요청할 수 있습니다.",
  icons: {
    icon: "/favicon.svg",
    shortcut: "/favicon.svg",
  },
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ko">
      <body className="antialiased">{children}</body>
    </html>
  );
}
