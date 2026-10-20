import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { filterVideos, initialVideos, readVideoCatalog, youtubeVideoId, youtubeEmbedUrl } from '../lib/content-library.ts';

const id = 'HgNJrIhC3rk';
const validUrls = [`https://youtu.be/${id}?si=abc`, `https://www.youtube.com/watch?v=${id}&t=1`, `https://m.youtube.com/shorts/${id}`, `https://youtube.com/live/${id}`, `https://www.youtube-nocookie.com/embed/${id}`];
const invalidUrls = ['javascript:alert(1)', `https://youtube.com.evil.test/watch?v=${id}`, `https://evil.test/?v=${id}`, `https://user@youtube.com/watch?v=${id}`, `https://youtu.be/${id}/other`, `https://youtube.com/watch?v=${id}extra`, 'https://youtube.com/playlist?list=abc'];

test('YouTube links normalize without accepting arbitrary URLs or markup', () => {
  for (const url of validUrls) assert.equal(youtubeVideoId(url), id, url);
  for (const url of invalidUrls) assert.equal(youtubeVideoId(url), null, url);
  assert.equal(youtubeEmbedUrl(id), `https://www.youtube-nocookie.com/embed/${id}?playsinline=1&rel=0`);
  assert.throws(() => youtubeEmbedUrl('bad?id=x'));
});
test('only a catalog protocol response is accepted; empty catalogs stay empty', () => {
  assert.equal(readVideoCatalog({ ok: true, service: 'quote' }), null);
  assert.equal(readVideoCatalog({ ok: false, catalogVersion: 1, items: [] }), null);
  assert.deepEqual(readVideoCatalog({ ok: true, catalogVersion: 1, items: [] }), []);
  const videos = readVideoCatalog({ ok: true, catalogVersion: 1, items: [
    { ...initialVideos[0], customerPhone: 'PRIVATE', secret: 'PRIVATE' },
    { ...initialVideos[0], title: 'duplicate' },
    { ...initialVideos[0], id: 'bad', url: 'javascript:alert(1)' },
    { ...initialVideos[0], id: 'blog', kind: 'blog' },
  ] });
  assert.equal(videos.length, 1);
  assert.equal('secret' in videos[0], false);
  assert.equal('customerPhone' in videos[0], false);
});
test('search combines title/channel with independent species and topic filters', () => {
  const videos = [...initialVideos, { ...initialVideos[0], id: 'b', title: '블루베리 전정', species: ['블루베리'], topics: ['전정'] }];
  assert.equal(filterVideos(videos, '나무심는', '전체', '식재').length, 1);
  assert.equal(filterVideos(videos, '나무중개사', '블루베리', '전정')[0].id, 'b');
  assert.equal(filterVideos(videos, '', '블루베리', '식재').length, 0);
});

function gasCatalog(rows, headers = ['콘텐츠ID','종류','제목','주소','묘목','주제','게시','순서','출처']) {
  const reads = [];
  const context = vm.createContext({
    PropertiesService: { getScriptProperties: () => ({ getProperty: key => key === 'LEGACY_SPREADSHEET_ID' ? 'ops-id' : null }) },
    SpreadsheetApp: { openById: book => {
      assert.equal(book, 'ops-id');
      return { getSheetByName: name => {
        assert.equal(name, '콘텐츠자료실');
        return { getLastRow: () => rows.length + 1, getRange: (r, c, nr, nc) => {
          reads.push([r,c,nr,nc]);
          assert.equal(c, 1); assert.equal(nc, 9);
          return { getValues: () => r === 1 ? [headers] : rows };
        } };
      } };
    } },
  });
  vm.runInContext(readFileSync(new URL('../server-support/ContentLibrary.gs', import.meta.url), 'utf8'), context);
  return { result: JSON.parse(JSON.stringify(vm.runInContext('publicContentLibrary_()', context))), reads, context };
}
test('Apps Script reads only the content tab and publishes explicit checked video rows', () => {
  const row = ['video-001','youtube','나무 심는방법',`https://youtu.be/${id}`,'공통','식재',true,1,'나무중개사'];
  const { result, reads } = gasCatalog([row,
    [...row.slice(0, 6), false, 2, 'hidden'],
    ['blog-001','blog','블로그','https://example.com','공통','식재',true,3,''],
    ['video-bad','youtube','bad','javascript:alert(1)','','',true,4,''],
  ]);
  assert.equal(result.ok, true);
  assert.equal(result.items.length, 1);
  assert.equal(result.items[0].url, `https://www.youtube.com/watch?v=${id}`);
  assert.equal(readVideoCatalog(result).length, 1);
  assert.deepEqual(reads, [[1,1,1,9],[2,1,4,9]]);
  for (const url of validUrls) assert.equal(vm.runInContext(`contentYoutubeId_(${JSON.stringify(url)})`, gasCatalog([]).context), id);
  for (const url of invalidUrls) assert.equal(vm.runInContext(`contentYoutubeId_(${JSON.stringify(url)})`, gasCatalog([]).context), null);
});
test('Apps Script empty/invalid/oversized sheets never leak other data', () => {
  assert.deepEqual(gasCatalog([]).result, { ok: true, catalogVersion: 1, items: [] });
  assert.equal(gasCatalog([], ['private']).result.ok, false);
  assert.equal(gasCatalog(Array(1001).fill([])).result.ok, false);
});
