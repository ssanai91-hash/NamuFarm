"use client";

import { useEffect, useRef, useState, type RefObject } from "react";
import { ArrowLeft, ExternalLink, LoaderCircle, Play, RotateCcw, Search, X } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Dialog, DialogClose, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { filterVideos, initialVideos, readVideoCatalog, youtubeEmbedUrl, type VideoResource } from "@/lib/content-library";

export default function VideoLibraryDialog({ open, onOpenChange, opener }: {
  open: boolean; onOpenChange: (open: boolean) => void; opener: RefObject<HTMLButtonElement | null>;
}) {
  const [videos, setVideos] = useState<VideoResource[]>(initialVideos);
  const [selected, setSelected] = useState<VideoResource | null>(null);
  const [query, setQuery] = useState("");
  const [species, setSpecies] = useState("전체");
  const [topic, setTopic] = useState("전체");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [revision, setRevision] = useState(0);
  const [synced, setSynced] = useState(false);
  const listScroll = useRef(0);
  const bodyRef = useRef<HTMLDivElement>(null);
  const selectedId = useRef<string | null>(null);
  const titleRef = useRef<HTMLHeadingElement>(null);
  const detailTitleRef = useRef<HTMLHeadingElement>(null);
  const lastCardId = useRef<string | null>(null);
  const cards = useRef(new Map<string, HTMLButtonElement>());

  useEffect(() => {
    if (!open) { setSelected(null); selectedId.current = null; return; }
    const controller = new AbortController();
    setLoading(true); setError("");
    fetch("/api/content-library", { cache: "no-store", signal: controller.signal }).then(async response => {
      if (!response.ok) throw new Error("Catalog unavailable");
      const data = await response.json();
      const valid = readVideoCatalog({ ok: true, catalogVersion: 1, items: data && typeof data === "object" && "videos" in data ? data.videos : null });
      if (!valid) throw new Error("Invalid catalog");
      if (controller.signal.aborted) return;
      setVideos(valid); setSynced(true);
      if (selectedId.current) {
        const current = valid.find(item => item.id === selectedId.current) ?? null;
        setSelected(current); selectedId.current = current?.id ?? null;
      }
      // A removed category must not leave the visitor stuck on an empty filter.
      setSpecies(current => current === "전체" || valid.some(v => v.species.includes(current)) ? current : "전체");
      setTopic(current => current === "전체" || valid.some(v => v.topics.includes(current)) ? current : "전체");
    }).catch(() => {
      if (!controller.signal.aborted) setError("목록을 갱신하지 못했어요. 기존에 등록된 영상을 표시합니다.");
    }).finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [open, revision]);

  const visible = filterVideos(videos, query, species, topic);
  const speciesOptions = ["전체", ...new Set(videos.flatMap(item => item.species).sort((a, b) => a.localeCompare(b, "ko")))];
  const topicOptions = ["전체", ...new Set(videos.flatMap(item => item.topics).sort((a, b) => a.localeCompare(b, "ko")))];
  function backToList() {
    selectedId.current = null; setSelected(null);
    requestAnimationFrame(() => {
      if (bodyRef.current) bodyRef.current.scrollTop = listScroll.current;
      if (lastCardId.current) cards.current.get(lastCardId.current)?.focus({ preventScroll: true });
    });
  }

  return <Dialog open={open} onOpenChange={onOpenChange}>
    <DialogContent className="video-library-dialog" showCloseButton={false}
      onOpenAutoFocus={event => { event.preventDefault(); titleRef.current?.focus(); }}
      onCloseAutoFocus={event => { event.preventDefault(); opener.current?.focus(); }}>
      <div className="video-library-heading">
        <DialogHeader>
          <DialogTitle ref={titleRef} tabIndex={-1}>묘목 재배 영상</DialogTitle>
          <DialogDescription>심기부터 관리까지, 필요한 영상을 찾아보세요.</DialogDescription>
        </DialogHeader>
        <DialogClose asChild><Button type="button" variant="outline" className="video-dismiss" aria-label="영상 자료실 닫기"><X aria-hidden="true" />닫기</Button></DialogClose>
      </div>
      <div className="video-library-body" ref={bodyRef}>
        {open && selected ? <div className="video-detail">
          <Button type="button" variant="ghost" className="video-back" onClick={backToList}><ArrowLeft />영상 목록</Button>
          <div className="video-player"><iframe key={selected.videoId} src={youtubeEmbedUrl(selected.videoId)}
            title={selected.title} allow="accelerometer; encrypted-media; gyroscope; picture-in-picture; web-share"
            allowFullScreen referrerPolicy="strict-origin-when-cross-origin" /></div>
          <h3 ref={detailTitleRef} tabIndex={-1}>{selected.title}</h3>
          {selected.source && <p className="video-source">출처 · {selected.source}</p>}
          <p className="video-play-help">재생되지 않으면 YouTube에서 시청해주세요.</p>
          <Button asChild variant="outline"><a href={selected.url} target="_blank" rel="noopener noreferrer">YouTube에서 보기<ExternalLink aria-hidden="true" /><span className="sr-only"> · 새 창</span></a></Button>
        </div> : <>
          <div className="video-search"><Search aria-hidden="true" /><Input type="search" aria-label="영상 제목·묘목·주제 검색" placeholder="영상 제목·묘목·주제 검색" value={query} onChange={e => setQuery(e.target.value)} maxLength={100} /></div>
          <div className="video-filters">
            <label>묘목<select value={species} onChange={e => setSpecies(e.target.value)}>{speciesOptions.map(v => <option key={v}>{v}</option>)}</select></label>
            <label>주제<select value={topic} onChange={e => setTopic(e.target.value)}>{topicOptions.map(v => <option key={v}>{v}</option>)}</select></label>
          </div>
          <div className="video-list-toolbar"><p role="status" aria-live="polite">{loading ? <><LoaderCircle className="animate-spin" />목록 확인 중…</> : `영상 ${visible.length}개`}</p><Button type="button" variant="ghost" disabled={loading} onClick={() => setRevision(v => v + 1)} aria-label="영상 목록 새로고침"><RotateCcw />새로고침</Button></div>
          {error && <p className="video-catalog-notice" role="status">{error}</p>}
          {!synced && !error && loading && <p className="sr-only">먼저 등록된 영상을 표시하고 있습니다.</p>}
          <div className="video-list">{visible.map(video => <button key={video.id} type="button" className="video-card" ref={element => { if (element) cards.current.set(video.id, element); else cards.current.delete(video.id); }} onClick={() => {
            lastCardId.current = video.id;
            listScroll.current = bodyRef.current?.scrollTop ?? 0;
            selectedId.current = video.id; setSelected(video);
            requestAnimationFrame(() => { if (bodyRef.current) bodyRef.current.scrollTop = 0; detailTitleRef.current?.focus({ preventScroll: true }); });
          }} aria-label={`${video.title} 재생`}>
            <span className="video-thumbnail"><img src={`https://i.ytimg.com/vi/${video.videoId}/hqdefault.jpg`} alt="" width={480} height={360} loading="lazy" referrerPolicy="no-referrer" /><span className="video-play-mark"><Play fill="currentColor" aria-hidden="true" /></span></span>
            <span className="video-card-copy"><strong>{video.title}</strong>{video.source && <span className="video-source">{video.source}</span>}<span className="video-tags">{[...video.species, ...video.topics].map((tag, i) => <span key={`${tag}-${i}`}>{tag}</span>)}</span></span>
          </button>)}</div>
          {!visible.length && <div className="video-empty"><p>{videos.length ? "조건에 맞는 영상이 없어요." : "등록된 영상을 준비하고 있어요."}</p>{videos.length > 0 && <Button type="button" variant="outline" onClick={() => { setQuery(""); setSpecies("전체"); setTopic("전체"); }}>검색 조건 초기화</Button>}</div>}
        </>}
      </div>
    </DialogContent>
  </Dialog>;
}
