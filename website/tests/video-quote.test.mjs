import { test } from 'node:test';
import assert from 'node:assert/strict';
import { initialVideos } from '../lib/content-library.ts';
import { emptyLine } from '../lib/quote-form.ts';
import { quoteSchema } from '../lib/quote-schema.ts';
import { appendVideoReference, stageVideoQuote, videoQuoteSpecies } from '../lib/video-quote.ts';

const quince = initialVideos.find(video => video.videoId === 'BCSPY_TAhmM');

test('the pictured quince video and Shorts use reviewed species; generic videos do not guess from titles', () => {
  assert.deepEqual(videoQuoteSpecies(quince), ['모과']);
  const blueberry = initialVideos.find(video => video.format === 'short' && video.species.includes('블루베리'));
  assert.deepEqual(videoQuoteSpecies(blueberry), ['블루베리']);
  assert.deepEqual(videoQuoteSpecies({ species: ['공통', '기타', '전체', ''] }), []);
  assert.deepEqual(videoQuoteSpecies({ species: ['모과나무', '모과', ' 모과나무 '] }), ['모과']);
});

test('several identified species stay separate without choosing an arbitrary cultivar or related plant', () => {
  assert.deepEqual(videoQuoteSpecies({ species: ['편백나무', '측백나무', '공통'] }), ['편백나무', '측백나무']);
  assert.deepEqual(videoQuoteSpecies({ species: ['초피나무', '산초나무', '참죽나무', '가죽나무'] }), ['초피나무', '산초', '참죽나무', '가죽나무']);
  assert.deepEqual(videoQuoteSpecies({ species: ['개복숭아', '복숭아나무'] }), ['개복숭아', '복숭아']);
  assert.equal(stageVideoQuote([emptyLine(0)], quince, '배', 1, false).staged, false);
});

test('opening a video quote fills one empty row with one seedling and keeps existing lines intact', () => {
  const other = { id: 1, species: '블루베리', quantity: '15', specification: '3년생' };
  const before = [other, emptyLine(2)];
  const result = stageVideoQuote(before, quince, '모과', 9, false);
  assert.equal(result.staged, true);
  assert.equal(result.added, true);
  assert.deepEqual(result.lines, [other, { id: 2, species: '모과', quantity: '1', specification: '' }]);
  assert.deepEqual(before, [other, emptyLine(2)]);
});

test('reopening the same seedling preserves the entered quantity and specification without toggling it off', () => {
  const lines = [{ id: 3, species: '모과나무', quantity: '27', specification: '왕모과 특묘 상담' }];
  const first = stageVideoQuote(lines, quince, '모과', 5, false);
  const again = stageVideoQuote(first.lines, quince, '모과', 6, false);
  assert.equal(again.staged, true);
  assert.equal(again.added, false);
  assert.equal(again.lineId, 3);
  assert.deepEqual(again.lines, lines);
});

test('pending receipts and twenty occupied rows prevent staging without losing customer input', () => {
  const full = Array.from({ length: 20 }, (_, id) => ({ id, species: `묘목${id}`, quantity: '10', specification: '보존' }));
  const limited = stageVideoQuote(full, quince, '모과', 20, false);
  assert.equal(limited.staged, false);
  assert.deepEqual(limited.lines, full);
  const pending = stageVideoQuote([emptyLine(0)], quince, '모과', 1, true);
  assert.equal(pending.staged, false);
  assert.deepEqual(pending.lines, [emptyLine(0)]);
});

test('video reference preserves the message, deduplicates URLs and respects normal and test-mode limits', () => {
  const message = '배송은 다음 달 희망합니다.';
  const combined = appendVideoReference(message, quince);
  assert.ok(combined.startsWith(message));
  assert.ok(combined.includes(quince.title) && combined.includes(quince.url));
  assert.equal(appendVideoReference(combined, quince), combined);
  assert.equal(appendVideoReference('가'.repeat(990), quince), '가'.repeat(990));
  assert.equal(appendVideoReference('가'.repeat(890), quince, 900), '가'.repeat(890));
});

test('staged seedlings use the unchanged strict quote contract without video-specific request fields', () => {
  const staged = stageVideoQuote([emptyLine(0)], quince, '모과', 1, false);
  const request = {
    requestId: '00000000-0000-4000-8000-000000000001', customerName: '화면검토', customerPhone: '010-0000-0000',
    region: '', items: staged.lines.map(({ species, specification, quantity }) => ({ species, specification, quantity: Number(quantity) })),
    message: appendVideoReference('', quince), processingBasis: 'quote_request', website: '',
  };
  assert.equal(quoteSchema.safeParse(request).success, true);
  assert.equal(quoteSchema.safeParse({ ...request, videoId: quince.videoId }).success, false);
});
