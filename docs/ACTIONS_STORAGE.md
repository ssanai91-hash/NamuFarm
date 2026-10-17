# Actions storage and downloads

## Retention and upload policy

- Regular Android CI runs tests and compiles debug. UI/test diagnostics upload only on failure or when `upload_ui_previews` is selected in a manual run; retention is 3 days. Diagnostic upload failure is non-blocking and does not mask test failures.
- Regular Desktop manager CI retains Linux/server tests and Windows Qt tests. EXE packaging/upload occurs only in a manual CI run with `build_exe` selected on main.
- Use **Android APK Build → Run workflow → debug/release** for a downloadable APK. Release uses the existing permanent signing certificate. Alternatively run Android CI manually with `build_signed_apk` selected on main.
- Use **Desktop EXE Build → Run workflow** for an EXE plus packaged Qt startup check. Use Desktop manager CI with `build_exe` selected for the full test gate before packaging. A successful manual EXE build alone does not mean the full test suite passed.
- APK/EXE artifacts expire after 7 days. On artifact upload failure, signed APKs and EXEs retain the existing private draft-release fallback. Debug upload failure remains a failure, so a debug build is never presented as downloadable when saving failed.
- Actions storage maintenance runs after producers finish, daily, and when its own workflow/script changes on main. Manual runs default to inventory only (`dry_run: true`); turn it off for cleanup.
- Maintenance lists all pages before deletion, keeps two newest downloadable binaries per kind (debug/signed/Windows), and keeps only the newest diagnostic bundle while younger than 3 days. Old and current debug/signed artifact names are treated as the same kind. Unknown artifacts, expired entries, releases, logs, caches, and other repositories are not deleted. Existing retained binaries still expire on their original GitHub expiry dates; this policy does not extend them.
- The maintenance run summary lists remaining artifacts and signed-in download URLs; its log records inventory, selected IDs, removed bytes and verified remaining bytes. These ZIP byte totals are repository inventory, not the account billing meter.

## Current investigation, 2026-10-17

- Existing APK/EXE producers already use private draft releases when Actions storage is blocked. All existing draft releases are preserved.
- Initial live inventory found 168 non-expired artifacts totaling 4,123,829,172 ZIP bytes, far above the 500 MB plan allowance quoted in the notification. The first cleanup deleted 133 artifacts (3,385,456,590 bytes). The complete inventory also identified the historical `namufarm-debug-apk` alias; it uses the same two-copy rule as `NamuFarm-Debug-APK`.
- Latest retained debug artifact from that inventory: `namufarm-debug-apk`, artifact `11528987315`, run `37729326650`, created October 8. It is an older test build, not a build of the current main branch. New APKs can be requested through Android APK Build.
- Latest observed signed APK: Android CI run `38998404125`, source `089d5c92`, draft tag `android-apk-38998404125-1`, `NamuFarm-release.apk` (14,236,304 bytes).
- Latest observed Windows EXE with full CI success: Desktop manager CI run `38981670608`, source `03a520b6`, draft tag `desktop-exe-38981670608-1`, `NamuFarm-PC.exe` (56,128,132 bytes).
- Open the repository **Releases** page while signed in to access draft assets. They remain private and have not been published.
- Desktop CI run `38998404099` failed before packaging: 9 test failures and 3 errors, including imports of `ManualQuoteDialog`, local sync behavior, and UI assertions. This is independent of artifact storage. This storage change does not suppress or repair those tests.
- The connected repository API does not expose the account billing meter. Check account Settings → Billing and licensing → Usage for the authoritative account-wide figure; other repositories and billing accrual may also contribute. Do not treat a repository ZIP total or the November 1 billing-cycle date as proof that the quota has cleared.
- GitHub documents a 6–12 hour delay for artifact-storage usage updates. New retention settings affect new uploads; maintenance explicitly removes old known duplicates.

References: https://docs.github.com/en/actions/how-tos/manage-workflow-runs/remove-workflow-artifacts and https://docs.github.com/en/billing/concepts/product-billing/github-actions
