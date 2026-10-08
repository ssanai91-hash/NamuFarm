import fs from 'node:fs/promises';

// Keep source evidence in the full manifest; ship only image paths to the browser.
const root = new URL('../', import.meta.url);
const manifest = JSON.parse(await fs.readFile(new URL('lib/species-illustrations.json', root), 'utf8'));
const catalog = JSON.parse(await fs.readFile(new URL('lib/species-data.json', root), 'utf8'));
const index = {};
for (const { id } of catalog) {
  const entry = manifest[id];
  if (!entry || !['illustrated', 'pending'].includes(entry.status)) throw new Error(`Missing illustration decision: ${id}`);
  if (entry.status === 'illustrated') {
    if (!/^\/plants\/v[0-9]+\/[a-z0-9-]+\.webp$/.test(entry.src)) throw new Error(`Invalid image path: ${id}`);
    index[id] = entry.src;
  } else {
    if (entry.src) throw new Error(`Pending illustration must have no image: ${id}`);
    index[id] = '';
  }
}
await fs.writeFile(new URL('lib/species-illustration-index.json', root), JSON.stringify(index, null, 2) + '\n');
