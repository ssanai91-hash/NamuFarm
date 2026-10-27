"use client";

import { useRef, useState, type RefObject } from "react";
import { ExternalLink, Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { BLOG_CATEGORIES, BLOG_PAGE_SIZE, BLOG_SOURCE, blogCatalogUpdatedAt, filterBlogPosts, initialBlogPosts } from "@/lib/blog-library";

export default function BlogLibraryDialog({ open, onOpenChange, opener }: {
  open: boolean; onOpenChange: (open: boolean) => void; opener: RefObject<HTMLButtonElement | null>;
}) {
  const [query, setQuery] = useState("");
  const [category, setCategory] = useState(0);
  const [limit, setLimit] = useState(BLOG_PAGE_SIZE);
  const bodyRef = useRef<HTMLDivElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const scrollTop = useRef(0);
  const articleTitles = useRef(new Map<string, HTMLHeadingElement>());
  const visible = filterBlogPosts(initialBlogPosts, query, category);
  const displayed = visible.slice(0, limit);

  function changeControls(nextQuery: string, nextCategory: number) {
    setQuery(nextQuery); setCategory(nextCategory); setLimit(BLOG_PAGE_SIZE);
    scrollTop.current = 0;
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }
  function handleOpenChange(next: boolean) {
    if (!next) scrollTop.current = bodyRef.current?.scrollTop ?? 0;
    onOpenChange(next);
  }
  function loadMore() {
    const nextId = visible[limit]?.id;
    setLimit(value => value + BLOG_PAGE_SIZE);
    requestAnimationFrame(() => { if (nextId) articleTitles.current.get(nextId)?.focus(); });
  }

  return <Dialog open={open} onOpenChange={handleOpenChange}>
    <DialogContent className="video-library-dialog blog-library-dialog" showCloseButton={false}
      onOpenAutoFocus={event => { event.preventDefault(); titleRef.current?.focus(); requestAnimationFrame(() => { if (bodyRef.current) bodyRef.current.scrollTop = scrollTop.current; }); }}
      onCloseAutoFocus={event => { event.preventDefault(); opener.current?.focus(); }}>
      <div className="video-library-heading">
        <DialogHeader>
          <DialogTitle ref={titleRef} tabIndex={-1}>묘목 정보·블로그</DialogTitle>
          <DialogDescription>제목과 요약을 살펴보고, 네이버 블로그에서 자세히 읽어보세요.</DialogDescription>
        </DialogHeader>
        <Button type="button" variant="outline" className="video-dismiss" onClick={() => handleOpenChange(false)} aria-label="블로그 글 목록 닫기"><X aria-hidden="true" />닫기</Button>
      </div>
      <div className="video-library-body" ref={bodyRef}>
        <div className="blog-categories" role="group" aria-label="블로그 카테고리">
          {[{ id: 0, label: "전체" }, ...BLOG_CATEGORIES.filter(item => initialBlogPosts.filter(post => post.categoryNo === item.id).length >= 2)].map(item => <Button type="button" key={item.id} variant="outline" aria-pressed={category === item.id} onClick={() => changeControls(query, item.id)}>
            <span>{item.label}</span><small>{item.id === 0 ? initialBlogPosts.length : initialBlogPosts.filter(post => post.categoryNo === item.id).length}개</small>
          </Button>)}
        </div>
        <div className="video-search"><Search aria-hidden="true" /><Input type="search" aria-label="블로그 제목·요약 검색" placeholder="글 제목·묘목 이름 검색" maxLength={100} value={query} onChange={event => changeControls(event.target.value, category)} /></div>
        <p className="blog-result-count" role="status" aria-live="polite">글 {visible.length}개 · {displayed.length}개 표시</p>
        <div className="blog-post-list">{displayed.map(post => <article key={post.id} className="blog-post-card" aria-labelledby={`blog-post-${post.id}`}>
          <div className="blog-post-meta"><span>{BLOG_CATEGORIES.find(category => category.id === post.categoryNo)?.label}</span><time dateTime={post.publishedAt}>{post.publishedAt.replaceAll("-", ".")}</time></div>
          <h3 id={`blog-post-${post.id}`} tabIndex={-1} ref={element => { if (element) articleTitles.current.set(post.id, element); else articleTitles.current.delete(post.id); }}>{post.title}</h3>
          <p>{post.summary}</p>
          <Button asChild variant="outline"><a href={post.url} target="_blank" rel="noopener noreferrer" aria-label={`${post.title} · 블로그에서 읽기 · 새 창`}>블로그에서 읽기<ExternalLink aria-hidden="true" /><span className="sr-only"> · 새 창</span></a></Button>
        </article>)}</div>
        {displayed.length < visible.length && <div className="video-load-more"><Button type="button" variant="outline" onClick={loadMore}>더 보기 ({Math.min(BLOG_PAGE_SIZE, visible.length - displayed.length)}개)</Button></div>}
        {!visible.length && <div className="video-empty"><p>조건에 맞는 글이 없어요.</p><Button type="button" variant="outline" onClick={() => changeControls("", 0)}>전체 글 보기</Button></div>}
        <p className="blog-source-note">출처 · {BLOG_SOURCE} · 목록 확인일 {blogCatalogUpdatedAt.replaceAll("-", ".")}<br />글은 작성 당시 정보입니다. 현재 가격과 재고는 견적 상담으로 확인해주세요.</p>
      </div>
    </DialogContent>
  </Dialog>;
}
