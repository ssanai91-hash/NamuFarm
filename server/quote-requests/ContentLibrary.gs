/** Public, read-only media catalog. No quote/customer data is returned. */
const CONTENT_LIBRARY_SHEET = '콘텐츠자료실';
const CONTENT_LIBRARY_HEADERS = ['콘텐츠ID','종류','제목','주소','묘목','주제','게시','순서','출처'];

function publicContentLibrary_() {
  try {
    const props = PropertiesService.getScriptProperties();
    const id = props.getProperty('LEGACY_SPREADSHEET_ID') || props.getProperty('SPREADSHEET_ID');
    if (!id) throw new Error('NOT_READY');
    const sheet = SpreadsheetApp.openById(id).getSheetByName(CONTENT_LIBRARY_SHEET);
    if (!sheet || sheet.getLastRow() > 1001) throw new Error('NOT_READY');
    const headers = sheet.getRange(1, 1, 1, CONTENT_LIBRARY_HEADERS.length).getValues()[0];
    if (JSON.stringify(headers) !== JSON.stringify(CONTENT_LIBRARY_HEADERS)) throw new Error('NOT_READY');
    const count = Math.max(0, sheet.getLastRow() - 1);
    const rows = count ? sheet.getRange(2, 1, count, CONTENT_LIBRARY_HEADERS.length).getValues() : [];
    const seen = new Set();
    const items = [];
    rows.forEach(row => {
      // Only explicitly published YouTube records go public. Blogs are reserved.
      if (!(row[6] === true || String(row[6]).trim().toLowerCase() === 'true') || String(row[1]).trim() !== 'youtube') return;
      const id = contentText_(row[0], 80), title = contentText_(row[2], 200);
      const videoId = contentYoutubeId_(String(row[3] || '').trim());
      if (!/^[A-Za-z0-9_-]+$/.test(id) || !title || !videoId || seen.has(id)) return;
      seen.add(id);
      items.push({ id: id, kind: 'youtube', title: title,
        url: 'https://www.youtube.com/watch?v=' + videoId,
        species: contentTags_(row[4]), topics: contentTags_(row[5]),
        order: row[7] !== '' && isFinite(Number(row[7])) ? Number(row[7]) : 9999,
        source: contentText_(row[8], 100) });
    });
    items.sort((a, b) => a.order - b.order || a.title.localeCompare(b.title, 'ko'));
    return { ok: true, catalogVersion: 1, items: items };
  } catch (error) {
    return { ok: false, catalogVersion: 1, code: 'CONTENT_UNAVAILABLE' };
  }
}
function contentText_(value, limit) {
  return String(value === null || value === undefined ? '' : value).replace(/[\x00-\x1f\x7f]/g, ' ').trim().slice(0, limit);
}
function contentTags_(value) {
  return Array.from(new Set(contentText_(value, 500).split(/[,，]/).map(v => v.trim().slice(0, 40)).filter(Boolean))).slice(0, 12);
}
function contentYoutubeId_(url) {
  if (typeof url !== 'string' || url.length > 2048) return null;
  let match = url.match(/^https?:\/\/(?:www\.)?youtu\.be\/([A-Za-z0-9_-]{11})(?:[?#].*)?$/i);
  if (match) return match[1];
  match = url.match(/^https?:\/\/(?:(?:www|m)\.)?youtube\.com\/watch\?([^#]*)(?:#.*)?$/i);
  if (match) { const id = match[1].match(/(?:^|&)v=([A-Za-z0-9_-]{11})(?:&|$)/); return id ? id[1] : null; }
  match = url.match(/^https?:\/\/(?:(?:www|m)\.)?youtube\.com\/(?:shorts|live|embed)\/([A-Za-z0-9_-]{11})\/?(?:[?#].*)?$/i);
  if (match) return match[1];
  match = url.match(/^https?:\/\/(?:www\.)?youtube-nocookie\.com\/embed\/([A-Za-z0-9_-]{11})\/?(?:[?#].*)?$/i);
  return match ? match[1] : null;
}
