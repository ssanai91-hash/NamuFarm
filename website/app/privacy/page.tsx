import type { Metadata } from "next";
import { quoteSettings } from "@/lib/quote-server";
import { quoteProcessingNotice } from "@/lib/privacy-content";

export const metadata: Metadata = { title: "개인정보 처리방침 | 나무팜" };

export default function PrivacyPage() {
  const { privacy } = quoteSettings();
  return <main className="privacy-page wrap">
    <a className="privacy-back" href="/">나무팜 홈으로</a>
    <h1>개인정보 처리방침</h1>
    <p className="privacy-lead">나무팜 홈페이지 견적 요청에 관한 안내입니다.</p>
    {privacy.length >= 30 ? <div className="privacy-text">{privacy}</div> : <>
      <div className="service-notice">온라인 견적 접수를 준비하고 있습니다. 접수 시작 전에 문의 연락처, 보유기간 및 파기 기준을 포함한 상세 안내를 게시합니다.</div>
      <section><h2>처리 목적과 항목</h2><p>{quoteProcessingNotice} 희망 묘목·규격·수량과 요청사항을 함께 확인하며, 견적 단계에서는 상세 배송 주소를 요구하지 않습니다.</p></section>
      <section><h2>처리 근거</h2><p>고객이 요청한 견적과 배송 상담에 필요한 정보는 개인정보 보호법 제15조 제1항 제4호에 따라 처리하는 방식으로 운영할 예정입니다. 이 안내를 확인하기 위한 별도 동의 체크는 요구하지 않습니다.</p></section>
      <section><h2>보유기간과 문의</h2><p>운영 기준을 확정한 뒤 이 페이지에 공개합니다. 현재 홈페이지에서는 견적 요청이 전송되지 않습니다.</p></section>
    </>}
  </main>;
}
