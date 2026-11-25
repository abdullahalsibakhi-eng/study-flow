CREATE TABLE remote_details(assessment_id TEXT PRIMARY KEY REFERENCES assessments(id), payload TEXT NOT NULL);
CREATE TABLE session_history(session_id TEXT PRIMARY KEY, payload TEXT NOT NULL, outcome TEXT NOT NULL, credited REAL NOT NULL DEFAULT 0, recorded TEXT NOT NULL);
CREATE INDEX history_recorded ON session_history(recorded);
INSERT INTO schema_version VALUES(2);
