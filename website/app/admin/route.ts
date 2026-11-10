import dashboard from "../../admin-dashboard/dashboard.html?raw";
import { chatGPTSignInPath, chatGPTSignOutPath, getChatGPTUser } from "../chatgpt-auth";

export const dynamic = "force-dynamic";

// Confirmed owner of the existing public Site. Authentication headers are
// supplied by Sites; signing in alone does not grant administrator access.
const ADMIN_EMAIL = "ssanai91@gmail.com";
const headers = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "private, no-store, max-age=0",
  "Vary": "Cookie, Authorization, oai-authenticated-user-id, oai-authenticated-user-email",
  "X-Robots-Tag": "noindex, nofollow, noarchive",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
};

function accessPage(title: string, message: string, href: string, action: string) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>${title} · 나무팜</title><style>body{margin:0;background:#f5f7f0;color:#29412b;font:18px/1.8 system-ui,sans-serif;min-height:100vh;display:grid;place-items:center}main{max-width:480px;padding:30px}h1{font-size:28px}p{color:#5a6d50}a{display:block;padding:14px 20px;border-radius:10px;text-decoration:none;text-align:center;background:#466431;color:white;margin-top:20px}a.home{background:white;color:#466431;border:1px solid #cfdac3}a:focus-visible{outline:3px solid #466431;outline-offset:4px}</style></head><body><main><h1>${title}</h1><p>${message}</p><a href="${href}" target="_top">${action}</a><a class="home" href="/" target="_top">나무팜 홈페이지</a></main></body></html>`;
}

export async function GET() {
  const user = await getChatGPTUser();
  if (!user) {
    return new Response(accessPage(
      "관리자 로그인",
      "나무팜 업무·운영 점검 화면입니다. 관리자 ChatGPT 계정으로 로그인해 주세요.",
      chatGPTSignInPath("/admin"),
      "ChatGPT로 로그인",
    ), { status: 401, headers });
  }
  if (user.email.toLowerCase() !== ADMIN_EMAIL) {
    return new Response(accessPage(
      "관리자 계정이 필요합니다",
      "현재 로그인한 계정에는 이 화면을 볼 권한이 없습니다. 관리자 계정으로 다시 로그인해 주세요.",
      chatGPTSignOutPath("/admin"),
      "다른 계정으로 로그인",
    ), { status: 403, headers });
  }
  return new Response(dashboard, { status: 200, headers });
}
