import dashboard from "../../admin-dashboard/dashboard.html?raw";
import { env } from "cloudflare:workers";

export const dynamic = "force-dynamic";

const headers = {
  "Content-Type": "text/html; charset=utf-8",
  "Cache-Control": "private, no-store, max-age=0",
  "Vary": "Authorization",
  "X-Robots-Tag": "noindex, nofollow, noarchive",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
  "Referrer-Policy": "no-referrer",
  "Content-Security-Policy": "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data:; font-src data:; connect-src 'none'; form-action 'none'; base-uri 'none'; frame-ancestors 'none'",
};

function accessPage(title: string, message: string, href: string, action: string) {
  return `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><meta name="robots" content="noindex,nofollow"><title>${title} · 나무팜</title><style>body{margin:0;background:#f5f7f0;color:#29412b;font:18px/1.8 system-ui,sans-serif;min-height:100vh;display:grid;place-items:center}main{max-width:480px;padding:30px}h1{font-size:28px}p{color:#5a6d50}a{display:block;padding:14px 20px;border-radius:10px;text-decoration:none;text-align:center;background:#466431;color:white;margin-top:20px}a.home{background:white;color:#466431;border:1px solid #cfdac3}a:focus-visible{outline:3px solid #466431;outline-offset:4px}</style></head><body><main><h1>${title}</h1><p>${message}</p><a href="${href}" target="_top">${action}</a><a class="home" href="/" target="_top">나무팜 홈페이지</a></main></body></html>`;
}

export async function GET(request: Request) {
  const values = env as unknown as Record<string, string | undefined>;
  const username = values.NAMUFARM_ADMIN_USERNAME;
  const password = values.NAMUFARM_ADMIN_PASSWORD;
  if (!username || !password) {
    return new Response(accessPage(
      "로그인 설정 확인 중",
      "관리자 로그인 설정을 확인하고 있습니다. 잠시 후 다시 접속해 주세요.",
      "/admin", "다시 접속",
    ), { status: 503, headers });
  }
  // Credentials remain in server runtime secrets, outside the dashboard HTML.
  const token = /^Basic\s+([^\s]+)$/i.exec(request.headers.get("Authorization") || "")?.[1];
  let credentials = "";
  try { if (token) credentials = atob(token); } catch { /* Invalid Basic credentials. */ }
  if (credentials !== `${username}:${password}`) {
    return new Response(accessPage(
      "관리자 로그인",
      "브라우저 로그인 창에서 아이디와 비밀번호를 입력해 주세요.",
      "/admin", "다시 로그인",
    ), { status: 401, headers: {
      ...headers,
      "WWW-Authenticate": 'Basic realm="NamuFarm Admin", charset="UTF-8"',
    } });
  }
  return new Response(dashboard, { status: 200, headers });
}
