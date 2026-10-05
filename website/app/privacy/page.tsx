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
      <section><h2>처리 목적과 항목</h2><p>{quoteProcessingNotice} 이름·전화번호·선택 이메일과 희망 묘목·규격·수량·요청사항은 견적 작성, 답변 및 고객이 결정한 구매·배송 처리에 사용합니다. 견적 단계에서는 상세 배송 주소를 요구하지 않습니다.</p></section>
      <section><h2>처리 근거</h2><p>고객이 요청한 견적과 배송 상담에 필요한 정보는 개인정보 보호법 제15조 제1항 제4호에 따라 처리하는 방식으로 운영할 예정입니다. 이 안내를 확인하기 위한 별도 동의 체크는 요구하지 않습니다.</p></section>
      <section><h2>보관과 자동 삭제</h2><p>고객정보는 구글시트에 저장하기 전에 암호화하고, 판매자 앱에서 복호화합니다. 홈페이지 문의는 접수일로부터 30일이 지나면 자동 삭제하며, 상담 목적이 끝나면 더 일찍 삭제합니다. 구매가 확정되면 문의 사본을 지우고 거래에 필요한 정보만 별도 판매 기록으로 관리합니다. 전자상거래 관련 법령이 적용되는 계약·대금 결제·상품 공급 기록은 5년, 소비자 불만·분쟁 처리 기록은 3년 동안 보관합니다. 해당 기간이 끝나면 개인정보를 삭제하고 개인을 연결하지 않는 판매 집계 자료만 남깁니다.</p></section><section><h2>문의와 권리 행사</h2><p>개인정보 문의·열람·정정·삭제 요청을 받을 공식 연락처를 확인한 뒤 접수를 시작합니다. 현재 홈페이지에서는 견적 요청이 전송되지 않습니다.</p></section>
    </>}
  </main>;
}
