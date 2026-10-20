import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { initialBlogPosts, readBlogCatalog, naverBlogPostId, filterBlogPosts, BLOG_CATEGORIES } from '../lib/blog-library.ts';

test('blog links only accept individual posts by the requested author', () => {
  const id = '224408210028';
  assert.equal(naverBlogPostId(`https://blog.naver.com/ohappyrich/${id}?trackingCode=rss`), id);
  assert.equal(naverBlogPostId(`https://m.blog.naver.com/ohappyrich/${id}`), id);
  for (const url of ['javascript:alert(1)', `https://blog.naver.com.evil.test/ohappyrich/${id}`,
    `https://blog.naver.com/other/${id}`, `https://user@blog.naver.com/ohappyrich/${id}`,
    'https://blog.naver.com/ohappyrich', `http://blog.naver.com/ohappyrich/${id}`,
    `https://blog.naver.com/ohappyrich/${id}/extra`]) assert.equal(naverBlogPostId(url), null, url);
});

test('only public catalog fields and the five requested categories are exposed', () => {
  const first = initialBlogPosts[0];
  const value = { catalogVersion: 1, items: [
    { ...first, phone: 'PRIVATE', fullBody: 'PRIVATE' },
    { ...first, url: first.url.replace('m.blog', 'blog') },
    { ...first, url: 'https://m.blog.naver.com/ohappyrich/123456789012', categoryNo: 12 },
    { ...first, url: 'https://m.blog.naver.com/ohappyrich/123456789013', summary: '' },
    { ...first, url: 'https://m.blog.naver.com/ohappyrich/123456789014', publishedAt: '2026-02-30' },
  ] };
  const result = readBlogCatalog(value);
  assert.equal(result.length, 1);
  assert.equal('phone' in result[0], false);
  assert.equal('fullBody' in result[0], false);
  assert.equal(readBlogCatalog({ items: [] }), null);
  assert.deepEqual(readBlogCatalog({ catalogVersion: 1, items: [] }), []);
});

test('category and normalized Korean search combine without changing the catalog', () => {
  const before = JSON.stringify(initialBlogPosts);
  assert.equal(filterBlogPosts(initialBlogPosts, '', 85).length, 46);
  assert.equal(filterBlogPosts(initialBlogPosts, '', 88).length, 1);
  assert.ok(filterBlogPosts(initialBlogPosts, '나무 팜', 88).length > 0);
  assert.equal(filterBlogPosts(initialBlogPosts, '존재하지않는제목', 0).length, 0);
  assert.equal(JSON.stringify(initialBlogPosts), before);
});

test('published snapshot exactly matches all collected NamuFarm category IDs', () => {
  const audit = JSON.parse(readFileSync(new URL('../docs/blog-catalog-20261020.json', import.meta.url), 'utf8'));
  assert.equal(initialBlogPosts.length, 205);
  assert.equal(new Set(initialBlogPosts.map(post => post.id)).size, 205);
  assert.deepEqual(initialBlogPosts.map(post => post.id).sort(), audit.includedPostIds.slice().sort());
  for (const category of BLOG_CATEGORIES) {
    const entry = audit.categories.find(item => item.categoryNo === category.id);
    assert.equal(initialBlogPosts.filter(post => post.categoryNo === category.id).length, entry.count);
    assert.equal(entry.count, entry.expectedCount);
    assert.ok(entry.count < entry.requestedItemCount);
    assert.deepEqual(initialBlogPosts.filter(post => post.categoryNo === category.id).map(post => post.id).sort(), entry.includedPostIds.slice().sort());
  }
  assert.ok(initialBlogPosts.every(post => post.summary.length >= 15 && post.summary.length <= 240));
});
