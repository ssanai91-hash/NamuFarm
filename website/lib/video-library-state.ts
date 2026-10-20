import type { VideoFormat, VideoResource } from "./content-library.ts";

export const VIDEO_PAGE_SIZE = 20;
export type VideoListState = {
  query: string; species: string; topic: string; limit: number;
  scrollTop: number; lastVideoId: string | null;
};
export type VideoLibraryState = {
  format: VideoFormat;
  lists: Record<VideoFormat, VideoListState>;
  selected: VideoResource | null;
};
type Controls = Pick<VideoListState, "query" | "species" | "topic">;
export type VideoLibraryAction =
  | { type: "format"; format: VideoFormat; scrollTop: number }
  | { type: "controls"; patch: Partial<Controls> }
  | { type: "more" }
  | { type: "select"; video: VideoResource; scrollTop: number }
  | { type: "back" }
  | { type: "scroll"; scrollTop: number }
  | { type: "catalog"; videos: VideoResource[] };

export function createVideoLibraryState(): VideoLibraryState {
  const list = (): VideoListState => ({ query: "", species: "전체", topic: "전체", limit: VIDEO_PAGE_SIZE, scrollTop: 0, lastVideoId: null });
  return { format: "video", lists: { video: list(), short: list() }, selected: null };
}

export function videoLibraryReducer(state: VideoLibraryState, action: VideoLibraryAction): VideoLibraryState {
  const current = state.lists[state.format];
  const update = (list: VideoListState): VideoLibraryState => ({ ...state, lists: { ...state.lists, [state.format]: list } });
  switch (action.type) {
    case "format": return { ...update({ ...current, scrollTop: action.scrollTop }), format: action.format, selected: null };
    case "controls": return update({ ...current, ...action.patch, limit: VIDEO_PAGE_SIZE, scrollTop: 0, lastVideoId: null });
    case "more": return update({ ...current, limit: current.limit + VIDEO_PAGE_SIZE });
    case "select": return { ...update({ ...current, scrollTop: action.scrollTop, lastVideoId: action.video.id }), selected: action.video };
    case "back": return { ...state, selected: null };
    case "scroll": return update({ ...current, scrollTop: action.scrollTop });
    case "catalog": {
      const lists = { ...state.lists };
      for (const format of ["video", "short"] as const) {
        const videos = action.videos.filter(video => video.format === format);
        const list = lists[format];
        const species = list.species === "전체" || videos.some(video => video.species.includes(list.species)) ? list.species : "전체";
        const topic = list.topic === "전체" || videos.some(video => video.topics.includes(list.topic)) ? list.topic : "전체";
        lists[format] = { ...list, species, topic };
      }
      const selected = state.selected ? action.videos.find(video => video.id === state.selected?.id && video.format === state.format) ?? null : null;
      return { ...state, lists, selected };
    }
  }
}
