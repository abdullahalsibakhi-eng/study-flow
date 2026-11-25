# Verification record

Verified November 19, 2026 on the development Mac. Results below were observed, not inferred from source existence.

## Automated backend tests

Command: Maven 3.9.11 `mvn -o -Dmaven.repo.local=/tmp/studyflow-m2 package`. Java runtime: OpenJDK 27; compiler release target: 21. Tests requiring loopback sockets ran with explicit environment approval outside the restricted sandbox.

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| EngineTest | 33 | 0 | 0 |
| IntegrationTest | 5 | 0 | 0 |
| OAuthTest | 4 | 0 | 0 |
| ScaleTest | 1 | 0 | 0 |
| StoreTest | 6 | 0 | 0 |
| SyncTest | 3 | 0 | 0 |

Total: **52 passing tests, zero failures/errors/skips**. Reports are generated under `backend/target/surefire-reports/` and are not committed build artifacts. Coverage percentage is not measured. The scale fixture contains 30 courses and 1,000 assessments and checks reconciliation, ranking, and collision-free scheduling within a test timeout; it is not a production benchmark.

## Native build and UI

The documented `scripts/build.sh` completed end to end using the downloaded Maven path and local dependency cache: tests, release compilation, helper packaging, and ad-hoc signing. `codesign --verify --deep --strict dist/StudyFlow.app` passed. The final packaged release was relaunched successfully into the empty normal workspace.

- Swift 6.4 release compilation succeeded with the installed macOS 27 Command Line Tools SDK, targeting macOS 14+. The toolchain emitted missing optional framework search-path linker warnings; it still linked successfully.
- The packaged, ad-hoc-signed native app launched and connected to Java. A fresh workspace showed zero courses/tasks and Disconnected Brightspace status.
- An isolated QA copy used `/tmp/studyflow-ui-verification` as its data directory. Through the actual UI, the agent created a course and a five-hour assessment with a 15% weight and personal notes.
- The saved assessment appeared in the assessment list and dashboard with a calculated priority and explanations.
- The planner generated three 50-minute sessions with ten-minute breaks and reported 2.5 hours unscheduled before the deadline.
- Pinning the first session preserved it while the remaining sessions were regenerated. The isolated SQLite file contained one course, one assessment, three sessions, and one pinned session.
- The calendar displayed the assessment and the three-session count on the corresponding day.
- The assistant returned local priorities with an explicit local-mode notice and no cloud configuration.
- Actual dashboard and planner PNGs are saved in `docs/screenshots/`. Only dark mode was visually inspected.
- The isolated QA app was quit after inspection; no QA records were seeded into the normal data directory.

Database reopening is tested automatically. A full GUI quit/relaunch persistence walkthrough was not performed. Keychain persistence with real credentials, live OAuth, live Brightspace, cloud-provider success, native notification permission/delivery, and login startup were not exercised.

## Release gates

The full original product requirements remain broader than this verified local release. See DELIVERY_STATUS.md. Human code review, dependency vulnerability scanning, older-OS compatibility, accessibility audit, institutional authorization, and distribution signing/notarization remain outstanding. No uptime, live sync accuracy, code coverage, API latency, or resource-consumption figures are claimed.


## Continuation verification — November 20, 2026

The existing implementation was extended in place after reviewing the master specification, prior implementation records, source/configuration files, and untracked Git state. Baseline: 52 passing tests. Final: **77 tests, zero failures, errors or skips**, verified from Surefire XML. Added suites cover scheduling lifecycle and migrations (7), contextual assistant answers (5), constrained calendar import (6), remote transport/mapping reliability (6), and import API behavior (1). Existing 52 tests still pass.

The complete `scripts/build.sh` pipeline passed with Maven 3.9.11 and the local dependency cache; the Swift release and packaged app built successfully. Deep strict ad-hoc signature verification passed. Build log: `/tmp/studyflow-nov20-build.log` (temporary local evidence).

### Isolated native walkthrough

A separate bundle, `app.studyflow.qa.nov20`, used `/tmp/studyflow-qa-nov20` and `STUDYFLOW_ISOLATED_TEST=1`, which disables restoring real Keychain credentials. No real account or production database was used.

- Opened a version-1 fixture successfully; SQLite inspection confirmed version 2 and a consistent `studyflow.db.before-v2.db` backup containing only schema version 1.
- Planner displayed the intentionally pinned session after its task deadline and an explicit conflict. An elapsed session appeared in unconfirmed history.
- Clicking Log completed work credited 1.00 hour and reduced unscheduled effort from 2.5 to 1.5 hours; assessment remaining effort changed from 5.0 to 4.0 hours. Exactly-once replay is additionally covered by automated tests.
- Local assistant returned the correct count of one assessment due in the next seven calendar days.
- Calendar file picker loaded the synthetic ICS fixture. Preview displayed December 1 at 1 PM Toronto time for the UTC event, disclosed start-to-deadline mapping, and kept import disabled until confirmation. After import the assessment list showed the new item with Calendar import source, two-hour estimate, and unknown weight.
- Light appearance was selected and visually inspected in the native Settings screen. Previous-session screenshots document dark appearance; a complete accessibility/contrast audit remains outstanding.
- Terminated only the isolated QA Java child. The native app recovered automatically, and a new assistant request returned a valid tomorrow preview respecting Saturday's unavailable study hours and the retained pin conflict. This verifies one recovery cycle, not prolonged uptime or repeated-failure exhaustion.

Live Brightspace authorization/token rotation, cloud-provider success, notification delivery, login startup and signed distribution remain unverified and require the external setup documented in DELIVERY_STATUS.md. No production-readiness, coverage percentage, or live synchronization accuracy claim is made.

## GitHub packaging review — November 24, 2026

Prepared a source-only copy, preserving the application code, tests, screenshots, workflow, and earlier verification records. Excluded Git history, generated bundles, build output, and local databases/configuration. Expanded `.gitignore` and clarified that the app must be built from source.

Shell-script syntax and Maven XML parsing passed. A scan for common credential patterns found no matches; this is not a comprehensive security audit. The backend suite was not rerun because Maven was not available in the current environment. A fresh native build attempt was blocked by access restrictions on the compiler cache before source compilation. The earlier 77-test and native-build results above are historical records, not results from this review.

## Course removal update — November 24, 2026

Downloaded Maven 3.9.11 into the workspace and reran the backend suite: **81 tests passed, zero failures/errors/skips**, including four new course-removal tests. Checks cover dependent records and history, preservation of other courses, remote-import suppression across restart, and confirmation enforcement with refreshed API state.

The updated native Swift release build succeeded using a writable compiler cache (two SDK search-path linker warnings). The new confirmation dialog has been compiled but not exercised through the live UI. No personal database was opened or modified. Live Brightspace authorization remains unverified and requires institution-issued registration credentials.
