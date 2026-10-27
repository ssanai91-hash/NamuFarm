"use client";

import { useEffect, useReducer, useRef, useState, type RefObject } from "react";
import { ArrowLeft, ExternalLink, FileText, LoaderCircle, Play, RotateCcw, Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { filterVideos, initialVideos, libraryVideos, readVideoCatalog, youtubeEmbedUrl, type VideoFormat, type VideoResource } from "@/lib/content-library";
import { createVideoLibraryState, videoLibraryReducer, VIDEO_PAGE_SIZE } from "@/lib/video-library-state";
import { videoQuoteSpecies } from "@/lib/video-quote";

export default function VideoLibraryDialog({ open, onOpenChange, opener, onRequestQuote, quoteOpen, quoteLocked }: {
  open: boolean; onOpenChange: (open: boolean) => void; opener: RefObject<HTMLButtonElement | null>;
  onRequestQuote: (video: VideoResource, opener: HTMLButtonElement) => void;
  quoteOpen: boolean; quoteLocked: boolean;
}) {
  const [catalog, setVideos] = useState<VideoResource[]>(initialVideos);
  const videos = libraryVideos(catalog);
  const [view, dispatch] = useReducer(videoLibraryReducer, undefined, createVideoLibraryState);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const bodyRef = useRef<HTMLDivElement>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const detailTitleRef = useRef<HTMLHeadingElement>(null);
  const cards = useRef(new Map<string, HTMLButtonElement>());
  const { selected, format } = view;
  const list = view.lists[format];

  useEffect(() => {
    if (!open) { dispatch({ type: "back" }); return; }
    const controller = new AbortController();
    setLoading(true); setError("");
    fetch("/api/content-library", { cache: "no-store", signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error("Catalog unavailable");
      const data = await response.json();
      const valid = readVideoCatalog({ ok: true, catalogVersion: 1, items: data && typeof data === "object" && "videos" in data ? data.videos : null });
      if (!valid) throw new Error("Invalid catalog");
      if (controller.signal.aborted) return;
      setVideos(valid);
      dispatch({ type: "catalog", videos: valid });
    }).catch(() => {
      if (!controller.signal.aborted) setError("목록을 갱신하지 못했어요. 기존에 등록된 영상을 표시합니다.");
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [open, revision]);

  const formatVideos = videos.filter(video => video.format === format);
  const visible = filterVideos(videos, list.query, list.species, list.topic, format);
  const displayed = visible.slice(0, list.limit);
  const speciesOptions = ["전체", ...new Set(formatVideos.flatMap(item => item.species).sort((a, b) => a.localeCompare(b, "ko")))];
  const topicOptions = ["전체", ...new Set(formatVideos.flatMap(item => item.topics).sort((a, b) => a.localeCompare(b, "ko")))];

  function restoreList(focusCard: boolean) {
    if (bodyRef.current) bodyRef.current.scrollTop = list.scrollTop;
    if (focusCard) {
      const card = list.lastVideoId ? cards.current.get(list.lastVideoId) : null;
      if (card) card.focus({ preventScroll: true }); else titleRef.current?.focus({ preventScroll: true });
    }
  }
  function backToList() {
    dispatch({ type: "back" });
    requestAnimationFrame(() => restoreList(true));
  }
  function handleOpenChange(nextOpen: boolean) {
    if (quoteOpen) return;
    if (!nextOpen && selected) { backToList(); return; }
    if (!nextOpen) dispatch({ type: "scroll", scrollTop: bodyRef.current?.scrollTop ?? 0 });
    onOpenChange(nextOpen);
  }
  function changeFormat(nextFormat: VideoFormat) {
    if (nextFormat === format) return;
    const target = view.lists[nextFormat];
    dispatch({ type: "format", format: nextFormat, scrollTop: bodyRef.current?.scrollTop ?? 0 });
    requestAnimationFrame(() => { if (bodyRef.current) bodyRef.current.scrollTop = target.scrollTop; });
  }
  function changeControls(patch: Partial<Pick<typeof list, "query" | "species" | "topic">>) {
    dispatch({ type: "controls", patch });
    if (bodyRef.current) bodyRef.current.scrollTop = 0;
  }
  function selectVideo(video: VideoResource) {
    dispatch({ type: "select", video, scrollTop: bodyRef.current?.scrollTop ?? 0 });
    requestAnimationFrame(() => { if (bodyRef.current) bodyRef.current.scrollTop = 0; detailTitleRef.current?.focus({ preventScroll: true }); });
  }

  return <Dialog open={open} onOpenChange={handleOpenChange}>
    <DialogContent className="video-library-dialog" showCloseButton={false}
      onOpenAutoFocus={event => { event.preventDefault(); titleRef.current?.focus(); requestAnimationFrame(() => restoreList(false)); }}
      onCloseAutoFocus={event => { event.preventDefault(); opener.current?.focus(); }}>
      <div className="video-library-heading">
        <DialogHeader>
          <DialogTitle ref={titleRef} tabIndex={-1}>묘목 재배 영상</DialogTitle>
          <DialogDescription>일반 영상과 짧은 영상에서 필요한 내용을 찾아보세요.</DialogDescription>
        </DialogHeader>
        <Button type="button" variant="outline" className="video-dismiss" onClick={() => handleOpenChange(false)} aria-label={selected ? "영상 닫고 목록으로 돌아가기" : "영상 자료실 닫기"}><X aria-hidden="true" />{selected ? "영상 닫기" : "닫기"}</Button>
      </div>
      <div className="video-library-body" ref={bodyRef}>
        {open && selected ? <div className="video-detail">
          <Button type="button" variant="ghost" className="video-back" onClick={backToList}><ArrowLeft />{selected.format === "short" ? "쇼츠 목록" : "영상 목록"}</Button>
          <div className={"video-player" + (selected.format === "short" ? " video-player-short" : "")}>{quoteOpen ? <p className="video-player-paused">견적을 작성하고 있어요.</p> : <iframe key={selected.videoId} src={youtubeEmbedUrl(selected.videoId)}
            title={selected.title} allow="accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share"
            allowFullScreen referrerPolicy="strict-origin-when-cross-origin" />}</div>
          <h3 ref={detailTitleRef} tabIndex={-1}>{selected.title}</h3>
          {selected.source && <p className="video-source">출처 · {selected.source}</p>}
          <p className="video-play-help">재생되지 않으면 YouTube에서 시청해주세요.</p>
          <div className="video-detail-actions"><Button asChild variant="outline"><a href={selected.url} target="_blank" rel="noopener noreferrer">{selected.format === "short" ? "YouTube에서 쇼츠 보기" : "YouTube에서 보기"}<ExternalLink aria-hidden="true" /><span className="sr-only"> · 새 창</span></a></Button>
            {videoQuoteSpecies(selected).length > 0 && <Button type="button" disabled={quoteLocked} aria-haspopup="dialog" onClick={event => onRequestQuote(selected, event.currentTarget)}><FileText aria-hidden="true" />{videoQuoteSpecies(selected).length === 1 ? "이 묘목 견적 요청" : "영상 속 묘목 견적 요청"}</Button>}
          </div>
          {quoteLocked && videoQuoteSpecies(selected).length > 0 && <p className="video-play-help" role="status">진행 중인 견적 접수 확인을 마친 뒤 새 묘목을 추가할 수 있어요.</p>}
        </div> : <>
          <div className="video-format-buttons" role="group" aria-label="영상 종류">
            {([["video", "일반 영상"], ["short", "쇼츠"]] as const).map(([value, label]) =>
              <Button key={value} type="button" variant="outline" aria-pressed={format === value} onClick={() => changeFormat(value)}>
                <span>{label}</span><small>{videos.filter(video => video.format === value).length}</small>
              </Button>)}
          </div>
          <div className="video-search"><Search aria-hidden="true" /><Input type="search" aria-label="영상 제목·묘목·주제 검색" placeholder="영상 제목·묘목·주제 검색" value={list.query} onChange={e => changeControls({ query: e.target.value })} maxLength={100} /></div>
          <div className="video-filters">
            <label>묘목<select value={list.species} onChange={e => changeControls({ species: e.target.value })}>{speciesOptions.map(v => <option key={v}>{v}</option>)}</select></label>
            <label>주제<select value={list.topic} onChange={e => changeControls({ topic: e.target.value })}>{topicOptions.map(v => <option key={v}>{v}</option>)}</select></label>
          </div>
          <div className="video-list-toolbar"><p role="status" aria-live="polite">{format === "short" ? "쇼츠 " : "영상 "}{visible.length}개{loading && <><LoaderCircle className="animate-spin" aria-hidden="true" /><span>확인 중…</span></>}</p><Button type="button" variant="ghost" disabled={loading} onClick={() => setRevision(v => v + 1)} aria-label="영상 목록 새로고침"><RotateCcw />새로고침</Button></div>
          {error && <p className="video-catalog-notice" role="status">{error}</p>}
          <div className={"video-list" + (format === "short" ? " video-short-list" : "")}>{displayed.map(video => <button key={video.id} type="button" className="video-card" ref={element => { if (element) cards.current.set(video.id, element); else cards.current.delete(video.id); }} onClick={() => selectVideo(video)} aria-label={video.title + " 재생"}>
            <span className="video-thumbnail"><img src={"https://i.ytimg.com/vi/" + video.videoId + (video.format === "short" ? "/oardefault.jpg" : "/hqdefault.jpg")} alt="" width={video.format === "short" ? 405 : 480} height={video.format === "short" ? 720 : 360} loading="lazy" referrerPolicy="no-referrer" onError={event => {
              const img = event.currentTarget;
              if (video.format === "short" && !img.dataset.fallback) { img.dataset.fallback = "true"; img.src = "https://i.ytimg.com/vi/" + video.videoId + "/hqdefault.jpg"; }
              else img.style.visibility = "hidden";
            }} /><span className="video-play-mark"><Play fill="currentColor" aria-hidden="true" /></span></span>
            <span className="video-card-copy"><strong>{video.title}</strong>{video.source && <span className="video-source">{video.source}</span>}<span className="video-tags">{[...video.species, ...video.topics].slice(0, 2).map((tag, i) => <span key={tag + "-" + i}>{tag}</span>)}</span></span>
          </button>)}</div>
          {displayed.length < visible.length && <div className="video-load-more"><p>{visible.length}개 중 {displayed.length}개 표시</p><Button type="button" variant="outline" onClick={() => dispatch({ type: "more" })}>더 보기 ({Math.min(VIDEO_PAGE_SIZE, visible.length - displayed.length)}개)</Button></div>}
          {!visible.length && <div className="video-empty"><p>{formatVideos.length ? "조건에 맞는 영상이 없어요." : "등록된 영상을 준비하고 있어요."}</p>{formatVideos.length > 0 && <Button type="button" variant="outline" onClick={() => changeControls({ query: "", species: "전체", topic: "전체" })}>검색 조건 초기화</Button>}</div>}
        </>}
      </div>
    </DialogContent>
  </Dialog>;
}
