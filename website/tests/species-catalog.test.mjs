import { test } from 'node:test';
import assert from 'node:assert/strict';
import { speciesCatalog, filterSpecies } from '../lib/species-catalog.ts';

test('varieties and growing methods share one selectable seedling name', () => {
  const names = speciesCatalog.map(species => species.name);
  for (const name of ['감', '대추', '매실', '목련', '무궁화', '무화과', '밤', '배', '복숭아', '뽕', '사철나무', '산초', '소나무', '앵두나무', '엄나무', '오가피', '자두', '키위', '포도', '포포나무', '회화나무']) {
    assert.equal(names.filter(value => value === name).length, 1, name);
  }
  assert.equal(names.length, 159);
  assert.equal(new Set(names).size, names.length);
  assert.ok(names.every(name => !/[()]/.test(name)));
  assert.ok(!names.includes('접목무궁화'));
  assert.ok(!names.includes('가이즈까향나무'));
});

test('original variety names and initials find the consolidated choice', () => {
  const examples = [
    ['엄나무(가시없는)', '엄나무'], ['엄나무(가시있는)', '엄나무'],
    ['오가피(가시없는)', '오가피'], ['오가피(가시있는)', '오가피'],
    ['키위(골드숫)', '키위'], ['키위(골드암)', '키위'],
    ['포도(거봉)포트', '포도'], ['포포나무(접목)', '포포나무'],
    ['소나무(백송)', '소나무'], ['소나무(황금소나무)', '소나무'],
    ['사철나무(황금)', '사철나무'], ['회화나무(황금)', '회화나무'],
    ['접목무궁화', '무궁화'],
  ];
  for (const [query, expected] of examples) {
    assert.deepEqual(filterSpecies('all', query).map(species => species.name), [expected], query);
  }
  assert.deepEqual(filterSpecies('all', 'ㅇㄴㅁ').map(species => species.name), ['엄나무', '옻나무']);
  assert.deepEqual(filterSpecies('ㅋ', '').map(species => species.name).filter(name => name === '키위'), ['키위']);
  assert.ok(!filterSpecies('ㄱ', '키위').length);
});

test('distinctly named seedlings retain their own buttons', () => {
  const names = new Set(speciesCatalog.map(species => species.name));
  for (const name of ['다래', '일세다래', '수국', '산수국', '아나벨수국', '떡갈잎수국', '라일락', '미스김라일락', '소나무', '금송', '구상나무', '해송', '대왕송', '포도', '포포나무']) {
    assert.ok(names.has(name), name);
  }
});
