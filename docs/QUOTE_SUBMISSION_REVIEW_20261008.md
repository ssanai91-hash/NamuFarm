# Quote submission recovery review — 2026-10-08

## Shipped website changes

- Close (including Escape/outside click) remains available while submitting or after uncertain failure. Closing only hides the dialog; the immutable pending payload and UUID survive for retry in the current tab.
- A main-form notice resumes verification. Editing a potentially saved request requires explicit confirmation that the seller checked it was not received; this does not cancel any server record.
- Receipt support calls use the existing footer mobile number. The general footer phone contacts are unchanged.
- Upstream HTTP errors and malformed/non-JSON responses are distinguished from network timeouts. Logs contain only a bounded diagnostic code, status and duration, never request bodies, credentials or response contents.
- An earlier ambiguous attempt stays pending even if a later HTTP attempt is locally rejected or rate limited.
- Includes the already deployed dialog centering CSS missing from main.

## Evidence and unresolved operational issue

Production logs showed seven POST /api/quote-requests responses with HTTP 503 in 260–600 ms. The configured Apps Script /exec URL returned HTTP 404 from script.google.com with and without query parameters. This establishes that the configured public endpoint was unavailable during the check, not whether any previous submission committed. The connected Drive account could not retrieve the recorded management spreadsheet and a NamuFarm search returned no results; deletion versus permissions/account mismatch is not established.

No customer submissions were generated and no stored customer rows were altered. Restoring the actual active Apps Script deployment/address and verifying its target spreadsheet is still required. Website UX/error handling deployment is not a backend repair or an end-to-end acceptance test.

## Performance review

The website already posts directly once without waiting for a readiness GET. Apps Script currently performs broad readiness checks, monthly/index lookups, multi-stage write/read verification, a summary update, and synchronous notification dispatch before responding. The script lock can also wait up to 15 seconds. Small payload size does not remove these round trips.

Recommended next server change: preserve UUID/fingerprint idempotency and verified durable commit, return the receipt immediately after commit, and defer notifications and summaries to existing maintenance triggers. Profile before weakening any recovery invariants. No Apps Script changes were deployed in this revision because the active endpoint is inaccessible.

Longer-term option: use an application database as the authoritative receipt/order store, with an outbox to asynchronously mirror records to Sheets. PC and Android must use the same API and identities; do not introduce two independent writable masters. This is a proposal, not a performed migration.

## Validation

24 website Node tests passed, TypeScript no-emit check passed, production build passed. New cases cover HTML 404, invalid JSON/login responses, mismatched receipts, conflicts, rate limits, and preserving pending identity after ambiguous attempts. Interactive browser QA and real successful Google submission remain unverified.

References: https://developers.google.com/apps-script/guides/support/best-practices ; https://developers.cloudflare.com/d1/
