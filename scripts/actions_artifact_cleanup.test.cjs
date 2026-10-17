'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const cleanup = require('./actions_artifact_cleanup.cjs');
const { planCleanup } = cleanup;
const now = Date.parse('2026-10-17T12:00:00Z');
const artifact = (id, name, days = 0, expired = false) => ({
  id, name, created_at: new Date(now - days * 86400000).toISOString(),
  expired, size_in_bytes: 100, workflow_run: { id: 1000 + id },
});

test('keeps two binaries per kind, coalesces signed aliases, preserves unknown and expired', () => {
  const items = [
    artifact(1, 'NamuFarm-Debug-APK', 20), artifact(2, 'NamuFarm-Debug-APK', 10),
    artifact(3, 'NamuFarm-Debug-APK'), artifact(4, 'NamuFarm-Signed-APK', 2),
    artifact(5, 'namufarm-release-apk', 1), artifact(6, 'namufarm-release-apk'),
    artifact(7, 'NamuFarm-PC-Windows', 40), artifact(8, 'NamuFarm-PC-Windows', 30),
    artifact(9, 'NamuFarm-PC-Windows', 20), artifact(10, 'other-file', 60),
    artifact(11, 'NamuFarm-PC-Windows', 0, true),
  ];
  assert.deepEqual(planCleanup(items, now).remove.map(a => a.id), [1, 4, 7]);
  assert.deepEqual(planCleanup(items, now).keep.map(a => a.id), [2, 3, 5, 6, 8, 9, 10]);
});
test('never removes a sole binary just because it is old or the run later failed', () => {
  const a = artifact(1, 'NamuFarm-Signed-APK', 60);
  a.workflow_run.conclusion = 'failure';
  assert.equal(planCleanup([a], now).remove.length, 0);
});
test('diagnostics retain only the newest bundle younger than three days', () => {
  const list = [artifact(1, 'namufarm-ui-review', 1), artifact(2, 'namufarm-ui-review', 2)];
  assert.deepEqual(planCleanup(list, now).remove.map(a => a.id), [2]);
  assert.equal(planCleanup([artifact(3, 'namufarm-ui-review', 3)], now).remove.length, 1);
});
test('timestamp ties use the newer id and do not depend on API ordering', () => {
  const list = [1, 3, 2].map(id => artifact(id, 'NamuFarm-Debug-APK'));
  assert.deepEqual(planCleanup(list, now).remove.map(a => a.id), [1]);
});
function harness(items) {
  const deleted = [];
  let lists = 0;
  const summary = {};
  for (const method of ['addHeading', 'addRaw', 'addTable']) summary[method] = () => summary;
  summary.write = async () => {};
  const listArtifactsForRepo = () => {};
  return { deleted, lists: () => lists,
    args: { context: { repo: { owner: 'ssanai91-hash', repo: 'NamuFarm' } },
      core: { info() {}, summary }, github: {
        paginate: async (method, params) => {
          assert.equal(method, listArtifactsForRepo);
          assert.equal(params.per_page, 100);
          lists++;
          return items.filter(a => !deleted.includes(a.id));
        }, rest: { actions: { listArtifactsForRepo,
          deleteArtifact: async ({ artifact_id }) => deleted.push(artifact_id) } },
      } },
  };
}
test('default dry run never deletes and sees artifacts beyond the first 100', async () => {
  const h = harness(Array.from({ length: 205 }, (_, i) => artifact(i + 1, 'NamuFarm-Debug-APK')));
  await cleanup(h.args);
  assert.deepEqual(h.deleted, []);
  assert.equal(h.lists(), 1);
});
test('live cleanup deletes only selected ids and verifies remaining inventory', async () => {
  const h = harness([1, 2, 3, 4].map(id => artifact(id, 'NamuFarm-Debug-APK')));
  await cleanup({ ...h.args, dryRun: false });
  assert.deepEqual(h.deleted, [1, 2]);
  assert.equal(h.lists(), 2);
});
test('wrong repository and incomplete inventory fail before any deletion', async () => {
  const h = harness([artifact(1, 'namufarm-ui-review', 20)]);
  await assert.rejects(cleanup({ ...h.args, context: { repo: { owner: 'other', repo: 'NamuFarm' } }, dryRun: false }));
  h.args.github.paginate = async () => { throw new Error('inventory interrupted'); };
  await assert.rejects(cleanup({ ...h.args, dryRun: false }));
  assert.deepEqual(h.deleted, []);
});
