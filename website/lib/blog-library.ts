import bundledBlog from "./blog-catalog.json" with { type: "json" };

export const BLOG_CATEGORIES = [
  { id: 88, label: "나무팜 안내", original: "나무팜안내(공지)" },
  { id: 61, label: "묘목 분양", original: "묘목분양 : 전국 도·소매" },
  { id: 85, label: "묘목 가이드", original: "묘목가이드 : 나무사전" },
  { id: 86, label: "식재·시공 사례", original: "조경공사 : 식재 및 시공사례" },
  { id: 87, label: "상차·배송", original: "상차·배송 현황" },
] as const;
export const BLOG_PAGE_SIZE = 20;
export const BLOG_SOURCE = "나무팜(Namu Farm) · 나무중개사";

export type BlogResource = {
  id: string;
  title: string;
  summary: string;
  categoryNo: number;
  url: string;
  publishedAt: string;
  source: string;
};

function plainText(value: unknown, max: number) {
  return typeof value === "string" ? value.replace(/[\u0000-\u001f\u007f]/g, " ").trim().slice(0, max) : "";
}

// Only the requested author's individual public posts are valid destinations.
export function naverBlogPostId(value: unknown): string | null {
  if (typeof value !== "string" || value.length > 2048) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "https:" || url.username || url.password || url.port ||
      !["blog.naver.com", "m.blog.naver.com"].includes(url.hostname)) return null;
    return url.pathname.match(/^\/ohappyrich\/(\d{10,15})\/?$/)?.[1] ?? null;
  } catch { return null; }
}

export function readBlogCatalog(value: unknown): BlogResource[] | null {
  if (!value || typeof value !== "object") return null;
  const body = value as Record<string, unknown>;
  if (body.catalogVersion !== 1 || !Array.isArray(body.items) || body.items.length > 1000) return null;
  const seen = new Set<string>();
  const posts: BlogResource[] = [];
  for (const raw of body.items) {
    if (!raw || typeof raw !== "object") continue;
    const item = raw as Record<string, unknown>;
    const id = naverBlogPostId(item.url);
    const title = plainText(item.title, 200), summary = plainText(item.summary, 240);
    const category = BLOG_CATEGORIES.find(category => category.id === item.categoryNo);
    const date = plainText(item.publishedAt, 10);
    if (!id || seen.has(id) || !title || !summary || !category ||
      !/^\d{4}-\d{2}-\d{2}$/.test(date) || !Number.isFinite(Date.parse(date)) ||
      new Date(date).toISOString().slice(0, 10) !== date) continue;
    seen.add(id);
    posts.push({ id, title, summary, categoryNo: category.id,
      url: `https://m.blog.naver.com/ohappyrich/${id}`, publishedAt: date, source: BLOG_SOURCE });
  }
  return posts.sort((a, b) => b.publishedAt.localeCompare(a.publishedAt) || b.id.localeCompare(a.id));
}

export const initialBlogPosts = readBlogCatalog(bundledBlog) ?? [];
export const blogCatalogUpdatedAt = bundledBlog.updatedAt;

export function filterBlogPosts(items: BlogResource[], query: string, categoryNo: number) {
  const normalize = (text: string) => text.normalize("NFKC").toLocaleLowerCase("ko").replace(/\s+/g, "");
  const needle = normalize(query.trim());
  return items.filter(item => (categoryNo === 0 || item.categoryNo === categoryNo) &&
    (!needle || normalize([item.title, item.summary, BLOG_CATEGORIES.find(category => category.id === item.categoryNo)?.original ?? ""].join(" ")).includes(needle)));
}
