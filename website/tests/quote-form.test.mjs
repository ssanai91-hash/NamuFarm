import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addCatalogLine, toggleCatalogLine, emptyLine, quoteFieldProblems } from '../lib/quote-form.ts';
import { quoteSchema, requestFingerprint } from '../lib/quote-schema.ts';

test('catalog insertion reuses empty rows and never increments duplicates', () => {
  const first = addCatalogLine([emptyLine(0)], '감(대봉)', 1);
  assert.equal(first.lines.length, 1);
  assert.equal(first.lines[0].id, 0);
  const second = addCatalogLine(first.lines, '포도', 2);
  const duplicate = addCatalogLine(second.lines, '감(대봉)', 3);
  assert.equal(duplicate.added, false);
  assert.deepEqual(duplicate.lines, second.lines);
  assert.equal(duplicate.lines[0].quantity, '');
});
test('20-row limit retains data, but permits filling an existing empty row', () => {
  const full = Array.from({length:20}, (_,i) => ({id:i,species:`묘목${i}`,quantity:'10',specification:''}));
  assert.deepEqual(addCatalogLine(full, '전나무', 99).lines, full);
  full[6] = emptyLine(6);
  const result = addCatalogLine(full, '전나무', 100);
  assert.equal(result.lines.length, 20);
  assert.equal(result.lines[6].id, 6);
  assert.equal(result.lines[6].species, '전나무');
});
test('catalog cards toggle off and on while preserving other entered items', () => {
  const chosen = {id:1,species:'감(대봉)',quantity:'30',specification:'2년생'};
  const other = {id:2,species:'포도',quantity:'12',specification:'키 1m'};
  const off = toggleCatalogLine([chosen, other], '감(대봉)', 3);
  assert.equal(off.added, false);
  assert.deepEqual(off.lines, [other]);
  assert.deepEqual(off.removed, [{line:chosen,index:0}]);
  const on = toggleCatalogLine(off.lines, '감(대봉)', 4);
  assert.equal(on.added, true);
  assert.equal(on.lines.filter(line => line.species === '감(대봉)').length, 1);
  assert.deepEqual(on.lines[0], other);
  assert.deepEqual(toggleCatalogLine([chosen], '감(대봉)', 5).lines, [emptyLine(5)]);
});
test('a selected card removes all matching manual rows even when the request is full', () => {
  const full = Array.from({length:20}, (_,i) => ({id:i,species:i<2?' 감(대봉) ':`묘목${i}`,quantity:String(i+1),specification:`규격${i}`}));
  const off = toggleCatalogLine(full, '감(대봉)', 20);
  assert.equal(off.removed.length, 2);
  assert.deepEqual(off.lines, full.slice(2));
  assert.ok(off.lines.every(line => line.species.trim() !== '감(대봉)'));
  assert.deepEqual(off.removed.map(item => item.line), full.slice(0,2));
});
const request = {requestId:'00000000-0000-4000-8000-000000000001',customerName:'화면검토',customerPhone:'010-0000-0000',region:'충북 옥천군',items:[{species:'감(대봉)',specification:'',quantity:10}],message:'',processingBasis:'quote_request',website:'',customerEmail:'',formalQuoteRequested:false,recipientName:'',replyChannel:'sms'};
test('quantity and conditional email errors lead to the exact live field', () => {
  const bad = quoteSchema.safeParse({...request,customerName:'',replyChannel:'email',formalQuoteRequested:true,items:[{...request.items[0],quantity:0}]});
  assert.equal(bad.success,false);
  const issues = quoteFieldProblems(bad.error.issues,[{...emptyLine(73),species:'감(대봉)'}]);
  assert.ok(issues.some(p=>p.id==='quantity-73' && p.message.includes('주')));
  assert.ok(issues.some(p=>p.id==='email'));
  assert.equal(issues[0].id,'quantity-73');
  assert.ok(issues.findIndex(p=>p.id==='name') < issues.findIndex(p=>p.id==='email'));
});
test('display-only catalog identifiers are excluded from the strict request schema', () => {
  assert.equal(quoteSchema.safeParse(request).success,true);
  assert.equal(quoteSchema.safeParse({...request,items:[{...request.items[0],speciesId:'215'}]}).success,false);
  const legacy = {...request,consent:true};delete legacy.processingBasis;
  const parsed = quoteSchema.parse(legacy);
  const {requestId,...payload} = parsed;
  assert.equal(requestFingerprint(payload),JSON.stringify(payload));
  assert.equal(parsed.consent,true);
});
