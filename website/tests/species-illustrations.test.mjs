import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const root = new URL('../', import.meta.url);
const json = async relative => JSON.parse(await fs.readFile(new URL(relative, root), 'utf8'));

test('every catalog ID has an explicit illustration decision', async () => {
  const species = await json('lib/species-data.json');
  const mapping = await json('lib/species-illustrations.json');
  const index = await json('lib/species-illustration-index.json');
  assert.equal(species.length, 185);
  assert.equal(new Set(species.map(item => item.id)).size, 185);
  assert.deepEqual(Object.keys(mapping).sort(), species.map(item => item.id).sort());
  assert.deepEqual(index, Object.fromEntries(Object.entries(mapping).map(([id, art]) => [id, art.status === 'illustrated' ? art.src : ''])), 'Run node scripts/sync-species-illustrations.mjs after editing the source manifest');
  for (const [id, art] of Object.entries(mapping)) {
    assert.ok(['illustrated', 'pending'].includes(art.status), id);
    if (art.status === 'pending') {
      assert.equal(art.src, '', `Pending ${id} must not borrow another plant's picture`);
      continue;
    }
    assert.match(art.src, /^\/plants\/v[0-9]+\/[a-z0-9-]+\.webp$/);
    assert.ok(art.depictedTaxon, `Depicted taxon missing: ${id}`);
    assert.ok(art.artworkScope, `Visual scope missing: ${id}`);
    assert.ok(art.references.length, `Botanical source missing: ${id}`);
    for (const reference of art.references) assert.equal(new URL(reference).protocol, 'https:');
    const bytes = await fs.readFile(new URL(`public${art.src}`, root));
    assert.equal(bytes.toString('ascii', 0, 4), 'RIFF');
    assert.equal(bytes.toString('ascii', 8, 12), 'WEBP');
    assert.ok(bytes.length < 60000, `Card image exceeds its 60 KB budget: ${art.src}`);
  }
});

test('uncertain supplier names stay neutral until their identity review is resolved', async () => {
  const pending = await json('docs/species-identity-review.json');
  const visual = await json('docs/species-visual-review.json');
  const mapping = await json('lib/species-illustrations.json');
  const species = await json('lib/species-data.json');
  assert.equal(Object.keys(pending).length, 37);
  for (const [id, review] of Object.entries({...pending, ...visual})) {
    assert.equal(species.find(item => item.id === id)?.name, review.name);
    assert.equal(mapping[id].status, 'pending');
    assert.equal(mapping[id].src, '');
    assert.ok(review.requiredEvidence);
  }
});
