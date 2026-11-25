# Local API contract

Base URL: `http://127.0.0.1:<port>/api/`. Every route requires `Authorization: Bearer <per-launch token>`. POST routes require JSON. Requests with a browser Origin are rejected. Maximum JSON request size is 1 MB. Responses are `Cache-Control: no-store`.

| Method | Route | Purpose |
|---|---|---|
| GET | health | Backend status and version |
| GET | state | Courses, assessments, rankings, stats, preferences, sessions, grade insights, sync state, notices, unmet work, source details, plan conflicts, session history, assistant-enabled flag |
| GET | oauth/status | Nonsecret authorization status |
| POST | courses | Create manual course from code, name, term |
| POST | assessments | Create/update validated Assessment record; remote source fields remain protected |
| POST | preferences | Save validated Preferences record and regenerate week |
| POST | plan | Generate `days` horizon (1–31); UI exposes today/week |
| POST | imports/calendar/preview | Validate content/courseId and return events/warnings without writes |
| POST | imports/calendar | Import validated content/courseId only with confirmEventStartsAsDeadlines=true |
| POST | sessions/complete | Exactly-once effort credit for an elapsed session id; then replan |
| POST | pin | Set session `id`, `pinned`; regenerate remaining sessions |
| POST | notices/read | Mark notices read |
| POST | sync | Request sync, subject to failure backoff |
| POST | brightspace/connect | Configure host, lpVersion, leVersion, accessToken in memory |
| POST | brightspace/disconnect | Stop OAuth/sync; frontend also clears Keychain |
| POST | oauth/start | Registered host/versions/clientId/clientSecret/scopes; returns official browser URL |
| POST | oauth/restore | Restore a Keychain credential document into memory |
| POST | assistant/configure | enabled, endpoint, model, key; disable with enabled=false |
| POST | assistant | question; returns text, cloud flag, notice |

The native caller owns persistence of manually entered AI/token-only credentials. OAuth rotation uses the packaged Keychain helper. No secret is included in GET state. There are no delete, coursework submission, grade modification, or remote write endpoints.

Errors: 400 invalid input, 401 unauthorized, 403 browser origin, 404 missing route, 405 unsupported method, 413 oversized body, 500 generic internal failure. Academic mutation routes are synchronous; syncing is asynchronous and observed through state. All server-owned scores and grade calculations must be treated as authoritative over model-generated explanations.

Wire schemas are the Java records in Domain.java and OAuthSession.Credentials; corresponding Swift Codable structs are in Models.swift. Unknown optional deadlines/grades are JSON null, not zero. Grade percent represents explicit final-course contribution, never raw points.

`SyncState.canSync` controls whether a manual sync is available; Connecting is distinct from a successfully verified Connected state. `remoteDetails` is keyed by assessment ID; it does not overwrite personal metadata. `sessionHistory` is the most recent 200 events. `planConflicts` explains preserved reservations outside current task constraints. Assistant replies remain read-only, including plan previews.

## Remove a course

`POST /api/courses/remove` with `{"id":"course-id","confirm":true}` requires the normal local bearer token. Missing confirmation or an unknown course returns 400. Removal of course data is transactional. Remote course IDs are persistently excluded from future reconciliation; nothing is deleted on Brightspace. The remaining plan is recalculated after removal.
