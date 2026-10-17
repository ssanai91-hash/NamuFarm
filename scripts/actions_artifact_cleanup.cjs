'use strict';

// Only these known, reproducible NamuFarm artifacts may be removed.
// Draft/published releases, logs, caches, unknown artifacts and other repos are untouched.
const groups = new Map([
  ['NamuFarm-Debug-APK', 'debug'],
  ['namufarm-debug-apk', 'debug'],
  ['NamuFarm-Signed-APK', 'signed'],
  ['namufarm-release-apk', 'signed'],
  ['NamuFarm-PC-Windows', 'windows'],
  ['namufarm-ui-review', 'diagnostics'],
]);

function planCleanup(artifacts, now = Date.now()) {
  const live = artifacts.filter(a => !a.expired);
  const keep = new Set();
  for (const group of new Set(groups.values())) {
    const members = live.filter(a => groups.get(a.name) === group)
      .sort((a, b) => Date.parse(b.created_at) - Date.parse(a.created_at) || b.id - a.id);
    if (group === 'diagnostics') {
      if (members[0] && now - Date.parse(members[0].created_at) < 3 * 86400000) {
        keep.add(members[0].id);
      }
    } else {
      // Keep the two newest downloadable binaries, even if their run later failed.
      for (const a of members.slice(0, 2)) keep.add(a.id);
    }
  }
  const remove = live.filter(a => groups.has(a.name) && !keep.has(a.id));
  return { remove, keep: live.filter(a => !remove.some(d => d.id === a.id)) };
}

async function cleanup({ github, context, core, dryRun = true }) {
  const { owner, repo } = context.repo;
  if (owner !== 'ssanai91-hash' || repo !== 'NamuFarm') {
    throw new Error('Cleanup is restricted to ssanai91-hash/NamuFarm.');
  }
  // Complete all pages before deciding what to delete.
  const artifacts = await github.paginate(github.rest.actions.listArtifactsForRepo,
    { owner, repo, per_page: 100 });
  const { remove, keep } = planCleanup(artifacts);
  const bytes = list => list.reduce((sum, a) => sum + a.size_in_bytes, 0);
  core.info(JSON.stringify({ dryRun, beforeBytes: bytes(artifacts.filter(a => !a.expired)),
    inventory: artifacts.map(a => ({ id: a.id, name: a.name, size: a.size_in_bytes,
      created: a.created_at, expired: a.expired, run: a.workflow_run?.id })),
    deleteIds: remove.map(a => a.id), keepIds: keep.map(a => a.id) }));
  for (const artifact of remove) {
    if (!dryRun) {
      await github.rest.actions.deleteArtifact({ owner, repo, artifact_id: artifact.id });
    }
    core.info(`${dryRun ? 'Would delete' : 'Deleted'} ${artifact.id}: ${artifact.name}`);
  }
  const remaining = dryRun ? artifacts.filter(a => !a.expired) :
    (await github.paginate(github.rest.actions.listArtifactsForRepo,
      { owner, repo, per_page: 100 })).filter(a => !a.expired);
  const mib = n => (n / 1048576).toFixed(2);
  await core.summary.addHeading('NamuFarm Actions artifact storage')
    .addRaw(`${dryRun ? 'DRY RUN' : 'CLEANUP'}: ${remove.length} artifacts / ${mib(bytes(remove))} MiB selected.\n\n`)
    .addRaw(`Repository artifact ZIP total: ${mib(bytes(artifacts.filter(a => !a.expired)))} → ${mib(bytes(remaining))} MiB. This is not account billing usage.\n\n`)
    .addRaw('Preserved: two newest artifacts per APK/EXE kind; one diagnostic bundle for up to 3 days; all unknown artifacts and all releases. Billing may take 6–12 hours to refresh.\n\n')
    .addTable([
      ['Artifact', 'MiB', 'Download (GitHub sign-in required)'],
      ...remaining.map(a => [a.name, mib(a.size_in_bytes),
        `https://github.com/${owner}/${repo}/actions/runs/${a.workflow_run?.id}/artifacts/${a.id}`]),
    ]).write();
  core.info(JSON.stringify({ afterBytes: bytes(remaining), afterCount: remaining.length,
    deletedCount: dryRun ? 0 : remove.length, deletedBytes: dryRun ? 0 : bytes(remove) }));
}

module.exports = cleanup;
module.exports.planCleanup = planCleanup;
