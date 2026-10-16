# GitHub device flow: can `user_code` pre-fill `/login/device`?

Backlog item b211f41e. Research only; no SteleKit source changes. Checked 2026-10-16.

## Answer

**No.** GitHub documents no `user_code` query parameter on `https://github.com/login/device`, and its `POST /login/device/code` response does not include `verification_uri_complete`.

## Evidence

1. **GitHub docs** — [Authorizing OAuth apps](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps), Device flow:
   - Step 2: "The app prompts the user to enter a user verification code at `https://github.com/login/device`."
   - The response table and the JSON/XML examples list exactly five fields: `device_code`, `user_code`, `verification_uri`, `expires_in`, `interval`. No `verification_uri_complete`.
2. **octokit** — the [`Verification` type](https://github.com/octokit/auth-oauth-device.js/blob/main/src/types.ts) has the same five fields.
3. **cli/oauth** — [`device_flow.go`](https://github.com/cli/oauth/blob/main/device/device_flow.go) (lines 45-46, 117) defines an optional `VerificationURIComplete`, read generically from `verification_uri_complete` (it also handles Google's `verification_url`). It parses the field if present; it is not evidence that GitHub sends it.
4. **Live request** (2026-10-16, unauthenticated): `curl -D - "https://github.com/login/device?user_code=ABCD-1234"` returned `HTTP/2 302` with `location: https://github.com/login?return_to=https%3A%2F%2Fgithub.com%2Flogin%2Fdevice%3Fuser_code%3DABCD-1234`.

## Caveats

- **Login redirect (partly observed):** the parameter survives the redirect to login. Whether the code field is actually pre-filled after login is unverified; it needs a real device code and a signed-in browser. No real `POST /login/device/code` was made (it needs an OAuth `client_id`).
- **URL exposure:** a `user_code` in the URL lands in browser history and logs. It expires in 900 s and is useless without the user's GitHub credentials.
- **Phishing:** device-flow phishing is a real threat independent of URL form.

## Optional follow-up

"Add forward-compatible `verification_uri_complete` parsing to `GitHubDeviceFlowClient`" (about 3h; no user-visible effect until GitHub ships the field).
