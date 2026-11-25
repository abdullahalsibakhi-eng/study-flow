# Delivery status and implementation checklist

## Completed and verified locally

- [x] Inspect original repository: empty, no source or project metadata to preserve.
- [x] Inspect toolchain: Java 27, Swift 6.4, Command Line Tools; obtain temporary Maven 3.9.11.
- [x] Java 21-compatible Maven backend, bounded authenticated local API.
- [x] Validated domain records, SQLite migration, transactional persistence and metadata separation.
- [x] Explainable scoring, weighted grade math, daily/weekly scheduling with pinned sessions.
- [x] Account-free tests for validation, persistence/restart, grade math, urgency, DST, overlap, reconciliation, duplicate prevention, OAuth state/rotation, backoff, and API failures.
- [x] Native SwiftUI release build, packaged .app, real launch, UI/backend data entry.
- [x] Actual dark-mode screenshots of dashboard and planner using isolated QA records.
- [x] Course creation, assessment save, automatic schedule generation, pinning, calendar display, offline assistant exercised through the real UI.
- [x] Brightspace documentation review and read-only adapter with paginated fixture tests.
- [x] Registered-app browser OAuth and token refresh implementation with simulated exchange tests.
- [x] Optional provider abstraction and tested graceful failure behavior.
- [x] README, architecture, API reference, truthful development/verification record.

## Implemented but awaiting environment-specific verification

- [ ] Carleton-approved OAuth registration, loopback redirect support, correct versions/scopes and student-role permissions.
- [ ] Live course/assessment fetch, token renewal, credential rotation through actual Keychain ACL approvals.
- [ ] Cloud-provider request with a user-enabled endpoint/key; cost and provider-specific behavior.
- [ ] Real macOS notification authorization and delivery, quiet hours over time, login item behavior across logout/login/sleep.
- [ ] Older macOS releases, Intel build, bundled Java runtime, Developer ID signing and notarization.
- [ ] Full VoiceOver and keyboard-only audit, light-mode visual audit, many-course UI stress inspection.

## Remaining product work

- [x] Brightspace displayable descriptions/instructions, availability windows and separate source point values.
- [ ] Brightspace gradebook category/rule mapping, published grades, submissions and personalized special-access due dates.
- [x] Local `.ics` file import with preview/confirmation, bounded parsing and idempotent course/UID mapping.
- [ ] Automatic calendar-feed subscriptions and recurring-event expansion.
- [x] Confirmed local course removal with dependent assessments, metadata, sessions and history; removed imported courses remain excluded from sync.
- [ ] Course editing/archiving, restoring excluded imported courses, and individual assessment deletion/archive controls.
- [ ] Editable calendar drag/drop, rich weekly time grid, user-created or moved pinned sessions.
- [x] Preserved-session conflict indicators, automatic replanning after elapsed sessions, durable unconfirmed history and exactly-once confirmed progress credit.
- [ ] Configurable academic targets/grading thresholds/category rules; current what-if uses an explicit 80% target only with a complete simple weighting scheme.
- [ ] Notification-category preferences, deep-link actions and priority-change alerts.
- [ ] Automated native UI regression suite; code coverage, startup, latency, memory and long-running sync benchmarks.
- [ ] Automated backup/import/export, release updater, branded icon assets, GitHub remote publication and CI execution.

The local functional core is usable. The complete production product described in the original request is not yet fully delivered; unfinished capabilities above are not represented as working. No institution credentials or access permissions were invented, and no real coursework was modified.

## November 20 continuation

- [x] Preserve the existing architecture, source tree, data model compatibility and prior tests; no project restart.
- [x] Transactional v2 migration with a consistent pre-migration SQLite backup.
- [x] Fix break boundaries around pinned sessions, preserve ongoing sessions, and stabilize generated IDs to deduplicate reminders.
- [x] Question-specific local assistant, complete aggregate counts, time-budget/day previews, and minimized cloud context.
- [x] Full-response HTTP time/size limits, HTTP-date Retry-After handling, strict pagination origin/path validation, truthful Connecting state, and separate synchronization/planning failure reporting.
- [x] Atomic Keychain updates and disconnect-before-delete ordering.
- [x] Native appearance controls, import preview, source details, study history and bounded child-process recovery.
- [x] 77 passing backend tests and successful release build. See VERIFICATION.md for native checks and their limits.

Live institution/provider access, published-grade ingestion, special-access overrides, recurring feeds, and commercial release qualification remain open. Calendar imports and local assistant calculations are usable independently of those credentials.
