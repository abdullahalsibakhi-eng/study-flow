# Connect StudyFlow to Brightspace

StudyFlow already implements browser-based OAuth sign-in. A normal student login alone cannot provision the app: a Brightspace administrator must register it first.

## What to request from your institution

Ask the Brightspace administrator whether they permit a personal, read-only academic-planner integration. Request an OAuth authorization-code registration named StudyFlow with:

- Redirect URI: `http://127.0.0.1:18744/oauth/callback`
- A client ID and client secret supplied privately
- Read scopes for your own enrollment, assignment folders and quizzes; the app defaults to `enrollment:own_enrollment:read dropbox:folders:read quizzing:quizzes:read`, which the administrator must verify against the institution's supported APIs
- Refresh tokens, if permitted
- The institution's Brightspace hostname and supported LP/LE API versions

Do not post the client secret, access tokens, or student password on GitHub or in chat.

## After registration

1. Build and launch the native app so the Keychain helper is available.
2. Open Settings → Brightspace connection.
3. Enter the approved hostname, API versions, client ID, secret, and scopes.
4. Choose **Sign In with Registered App**.
5. Complete your institution's login and consent in the browser, then return to StudyFlow.
6. Check sync status and confirm that your courses and assessments match Brightspace.

The administrator must allow the exact loopback redirect. A declined registration or unavailable permissions cannot be fixed by changing a login button. Live institution sign-in has not yet been verified for this project.

## If API access is unavailable

Use StudyFlow locally. If your institution provides an authorized `.ics` calendar export, use **Import Calendar** to preview and import it. This is a manual import, not a live Brightspace connection.

Reference: [D2L OAuth registration and authorization](https://docs.valence.desire2learn.com/basic/oauth2.html).
