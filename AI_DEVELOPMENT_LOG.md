# AI-assisted engineering log

## November 19, 2026 — initial implementation

The repository was empty. The assistant inspected files, toolchains, and official D2L documentation; designed the architecture; generated Java/Swift source, tests and build files; ran builds; inspected failures; and exercised the native UI using computer-use tools.

### Accepted engineering decisions

- Java owns all calculations, persistence, network integrations and local endpoints. SwiftUI is the native presentation layer.
- Use JDK HttpServer and explicit JDBC rather than Spring Boot/JPA for a small local process.
- Treat absent grade weights as unknown and separate points from final-course percentage weights.
- Preserve remote-disappeared records and user metadata; reconcile only complete snapshots.
- Keep cloud AI optional and read-only with limited context and explicit opt-in.
- Use personal registered-app OAuth credentials supplied by the user; store rotated tokens through a native Keychain helper via stdin.
- Use an isolated QA app/database for UI data entry instead of seeding fake academic data in production.

### Alternatives rejected or deferred

- JavaFX/Swing and browser UI were rejected because a native SwiftUI frontend was required.
- Scraping, session extraction, password capture, automatic coursework submission, and invented API access were excluded.
- Arbitrary assessment-type scoring bonuses and treating raw points as grade weights were rejected.
- Full optimizer claims were rejected: the scheduler is explicitly a greedy heuristic.
- A hidden LaunchAgent was not installed; the visible menu-bar app owns the backend process.
- Gradebook rules, individualized deadlines, imports, and release distribution remain unfinished and are listed in delivery status.

### Debugging assistance and fixes

- Downloaded Maven after discovering it was not installed; sandbox network access required explicit tool approval.
- Fixed a Java lambda capture compilation error.
- Tests exposed an SQLite placeholder-count mismatch; corrected it and reran persistence/API tests.
- Adjusted DST handling to resolve availability boundaries in wall-clock time.
- Swift compilation exposed a missing SDK State macro plugin; an explicit alias selects the existing stable property-wrapper implementation.
- Corrected Swift property-wrapper declarations and a conditional ShapeStyle type mismatch.
- Loopback integration tests required running outside the restricted sandbox; no test authentication was disabled.
- Added parent-process ownership cleanup, OAuth single-use state and rotation tests, rate-limit/backoff tests, disconnect-race tests, and a 1,000-assessment backend fixture.

### Verification performed

See docs/VERIFICATION.md for exact outcomes. Native launch, course creation, assessment editing, scheduler output, pinning, calendar display, and local assistant output were inspected by the AI agent in the running app. Screenshots were captured from that app. No human review is claimed. No live Brightspace or paid cloud-AI request was performed.

### Human verification still needed

Review source and threat model; obtain authorized institution registration; confirm actual API behavior, accommodations, and grade rules; authorize Keychain/helper access and notifications; check login startup; inspect light mode, VoiceOver and keyboard flows; run signed/notarized deployment tests and measure production reliability. No resume coverage or performance numbers should be inferred from test count alone.

## November 20, 2026 — reliability and daily-use continuation

Reviewed the original conversation/specification, Java and Swift sources, tests, build configuration, untracked Git state, delivery checklist and verification records. Preserved existing components and prior behavior, then implemented source details, full-response HTTP limits, accurate sync state, constrained calendar import, study-session history, missed-session replanning, question-specific assistant calculations and native presentation controls.

Regression tests identified the singular “one hour” routing defect; fixed it. Review identified the break-before-pinned-session collision case, unstable generated reminder IDs, and a refresh-versus-Keychain-deletion race; added focused fixes and regression coverage where backend-testable. Added a pre-migration SQLite backup and a test of actual v1-to-v2 preservation. No institution credentials, cloud keys, API permissions or live success were fabricated.

Tests now cover calendar preview/confirmation, import replays/metadata preservation, RFC date formats and unsupported recurrence, authoritative assistant counts beyond the context cap, time budgets, source visibility, slow-body timeouts, source-details persistence, elapsed-session history, exact-once credit and pinned conflicts. Native UI verification is recorded separately in VERIFICATION.md. No human review or production uptime is claimed.

Final November 20 validation: all 77 tests passed, packaged release built and signature checked. Isolated native walkthrough verified migration backup, pin conflict, elapsed-session credit, local assistant counts, confirmed ICS import, light appearance, and one automatic Java-child recovery cycle. Full evidence and limits are in docs/VERIFICATION.md.

## November 24, 2026 — course removal

Added confirmed local course removal across the SwiftUI screen, authenticated API, and transactional store. Dependent assessments, metadata, source details, sessions and history are removed; imported course IDs remain excluded from reconciliation across restarts. Added four regression tests, clarified OAuth registration requirements in Settings, and added an institution setup guide. All 81 backend tests and the native release build passed. No live OAuth success or UI walkthrough is claimed for this change. Changes were prepared in a separate GitHub-ready copy, not installed into the user's running application.
