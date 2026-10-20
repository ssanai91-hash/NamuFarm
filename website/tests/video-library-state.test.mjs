import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVideoLibraryState, videoLibraryReducer as reduce, VIDEO_PAGE_SIZE } from '../lib/video-library-state.ts';

const short = { id: 'shorts-12345678901', videoId: '12345678901', kind: 'youtube', format: 'short', title: '블루베리', url: 'https://www.youtube.com/shorts/12345678901', species: ['블루베리'], topics: ['가격·구입'], source: '나무중개사', order: 1001 };

test('first visit opens regular videos with twenty records', () => {
  const state = createVideoLibraryState();
  assert.equal(state.format, 'video');
  assert.equal(state.lists.video.limit, 20);
  assert.equal(state.lists.short.limit, 20);
  assert.equal(state.selected, null);
});

test('closing a loaded Short returns to its filtered, expanded list and preserves both tabs', () => {
  let state = createVideoLibraryState();
  state = reduce(state, { type: 'controls', patch: { query: '소나무', species: '소나무', topic: '전정' } });
  state = reduce(state, { type: 'more' });
  state = reduce(state, { type: 'format', format: 'short', scrollTop: 440 });
  state = reduce(state, { type: 'controls', patch: { query: '듀크', species: '블루베리', topic: '가격·구입' } });
  state = reduce(state, { type: 'more' });
  state = reduce(state, { type: 'more' });
  state = reduce(state, { type: 'select', video: short, scrollTop: 1200 });
  assert.equal(state.selected.id, short.id);
  state = reduce(state, { type: 'back' });
  assert.equal(state.selected, null);
  assert.equal(state.format, 'short');
  assert.deepEqual(state.lists.short, { query: '듀크', species: '블루베리', topic: '가격·구입', limit: 60, scrollTop: 1200, lastVideoId: short.id });
  state = reduce(state, { type: 'format', format: 'video', scrollTop: 1250 });
  assert.deepEqual(state.lists.video, { query: '소나무', species: '소나무', topic: '전정', limit: 40, scrollTop: 440, lastVideoId: null });
  state = reduce(state, { type: 'format', format: 'short', scrollTop: 450 });
  assert.equal(state.lists.short.scrollTop, 1250);
  assert.equal(state.lists.short.limit, 60);
});

test('new search resets only the active list to its first twenty records', () => {
  let state = createVideoLibraryState();
  state = reduce(state, { type: 'more' });
  state = reduce(state, { type: 'format', format: 'short', scrollTop: 90 });
  state = reduce(state, { type: 'more' });
  state = reduce(state, { type: 'controls', patch: { query: '새 검색' } });
  assert.equal(state.lists.short.limit, VIDEO_PAGE_SIZE);
  assert.equal(state.lists.short.scrollTop, 0);
  assert.equal(state.lists.video.limit, 40);
});

test('unpublishing the selected video removes its player and reconciles unavailable categories', () => {
  let state = createVideoLibraryState();
  state = reduce(state, { type: 'format', format: 'short', scrollTop: 0 });
  state = reduce(state, { type: 'controls', patch: { species: '블루베리', topic: '가격·구입' } });
  state = reduce(state, { type: 'select', video: short, scrollTop: 500 });
  state = reduce(state, { type: 'catalog', videos: [] });
  assert.equal(state.selected, null);
  assert.equal(state.lists.short.species, '전체');
  assert.equal(state.lists.short.topic, '전체');
});
