# Auth Bootstrap Failure Runbook

Use this runbook when `zap_auth_session_prepare`, `zap_auth_session_validate`,
or an authenticated guided scan fails.

Check the support boundary before changing configuration. An unsupported flow
requires a supported alternative or an implementation change.

## Current Support Boundary

Supported flows:

- form-login bootstrap backed by ZAP context and user configuration
- bearer/API-key profile preparation at the gateway layer
- guided `zap_auth_session_prepare` and `zap_auth_session_validate`
- authenticated guided HTTP crawl and active scan with prepared form-login sessions
- authenticated direct or queued Client Spider crawl (`strategy=client`) with
  prepared browser sessions

Important caveat:

- bearer/API-key sessions validate their configured credential references, but current guided ZAP
  flows do not automatically inject those headers into engine execution yet

Unsupported today:

- AJAX guided crawl (`strategy=browser`) with `authSessionId`
- prepared browser sessions with `auto`, HTTP, AJAX, or guided active scans
- MFA, SSO, CAPTCHA, device-code, or step-up login flows
- generic customer-specific auth adapters without implementation work
- automatic repair of broken login indicators or target-side auth behavior

## First Triage Pass

Capture these before changing anything:

- `correlationId` from the client response or request headers
- tool name: `zap_auth_session_prepare`, `zap_auth_session_validate`,
  `zap_crawl_start`, or `zap_attack_start`
- `profileId` and the profile's configured auth kind
- `targetUrl`
- whether the session reports `Engine Binding: ZAP context/user ready` or
  `Engine Binding: gateway contract only`
- the exact `Outcome` and `Diagnostics` lines from validation

Then classify the failure:

| Symptom | Likely Cause | First Action |
| --- | --- | --- |
| `Unknown auth profile ID` | Caller selected a profile that is not configured | Use an operator-configured profile ID or add the profile before retrying. |
| `Auth profile ... is invalid` | Operator profile is incomplete or internally inconsistent | Fix the profile configuration and restart before exposing the service. |
| `targetUrl cannot be null or blank` | Missing target URL | Supply the target host or base URL. |
| `targetUrl origin is not authorized for auth profile` | Caller selected a target outside the profile's exact scheme, host, and port | Use a target path on the configured origin or select the correct profile. |
| `loginUrl origin is not authorized for auth profile` | Operator configured a form login URL outside the credential's allowed origin | Correct the profile; never widen the origin merely to make the request pass. |
| `loginUrl cannot be null or blank` | Form profile is incomplete | Configure the actual login form URL in the profile. |
| `username cannot be null or blank` | Form profile username missing | Configure the login username in the profile. |
| `loggedInIndicatorRegex cannot be null or blank` | Form profile has no success indicator | Configure a regex that appears only after login. |
| `credentialReference file path must be absolute` | Relative secret path | Use `file:/absolute/path`. |
| `Auth profile credential could not be resolved` | Configured environment variable or secret file is missing, blank, or unreadable | Inspect the correlated operator log for the exact environment name or file path, then fix deployment injection or mount permissions. Do not return that metadata to MCP callers. |
| `authentication_failed` | ZAP could not authenticate the configured user | Check credentials, login URL, field names, and login indicators. |
| `usernameField contains unsupported characters` or `passwordField contains unsupported characters` | Form field name could inject extra ZAP auth config parameters | Use simple field names containing letters, numbers, dot, underscore, dash, colon, or brackets. |
| `Unknown auth session ID` | Session ID lost or wrong | Re-run `zap_auth_session_prepare`. |
| `form-login sessions only` | Header session passed to an HTTP crawl or active scan | Prepare a form-login session for authenticated HTTP crawling or active scanning. |
| `strategy=client requires a prepared browser authentication session` | Wrong profile kind for Client Spider | Prepare a `kind=browser` profile and use `strategy=client`. |
| `Prepared browser authentication sessions currently require strategy=client` | Browser session used with another strategy or active scan | Use `strategy=client` for this session; prepare a separate form profile for HTTP crawl or active scan. |
| `strategy=browser (AJAX Spider) does not support authSessionId` | Prepared session passed to AJAX Spider | Use Client Spider with a browser profile or HTTP crawl with a form profile. |

## Form-Login Prepare Failures

Form-login preparation needs all of this:

- operator-configured `profileId`
- `targetUrl`
- profile `allowed-origin`
- profile `login-url`
- profile `username`
- exact profile `credential-reference`
- profile `logged-in-indicator-regex`

Optional profile settings:

- profile `zap-user-name`
- profile `username-field`
- profile `password-field`
- profile `logged-out-indicator-regex`

If preparation fails before a session ID is returned, fix input or secret
resolution first. There is no session to validate yet.

If preparation returns a session but validation fails:

1. Confirm the credential source resolves inside the MCP runtime, not only on
   the operator laptop.
2. Confirm `login-url` is the form POST entrypoint ZAP can use.
3. Confirm `username-field` and `password-field` match the form field names.
4. Confirm `logged-in-indicator-regex` appears only after successful login.
5. Confirm `logged-out-indicator-regex`, if supplied, does not also match
   authenticated pages.
6. Do not mutate a guided profile's managed context with lower-level tools.
   Fix the profile and prepare a new session; use separate contexts for expert flows.

Validation output should include:

- `likelyAuthenticated=...`
- `contextId=...`
- `userId=...`

Continue to an authenticated scan only after validation reports
`likelyAuthenticated=true`.

## Authorized Target Checklist: Form-Login Prepare And Validate

Use this checklist before calling an authenticated pilot ready. It validates
form-login prepare and validate behavior against any authorized target. It is
not a reason to capture real credentials in docs or tickets.

Operator profile:

- `id=target-form`
- `kind=form`
- `allowed-origin=https://app.example.test`
- `credential-reference=env:TARGET_SCAN_PASSWORD` or
  `credential-reference=file:/absolute/path/to/mounted/secret`
- credential references are exact; wildcards and inline secrets are not supported
- derived ZAP context `target-form-auth` (profile ID plus `-auth`)
- `login-url=https://app.example.test/login`
- `username=<scan-user-name>`
- `username-field=<login-form-username-field>`
- `password-field=<login-form-password-field>`
- field names may contain letters, numbers, dot, underscore, dash, colon, or
  brackets only; ZAP auth config values are URL-encoded by the server
- `logged-in-indicator-regex=<text-only-visible-after-login>`
- optional `logged-out-indicator-regex=<text-only-visible-before-login>`

Inputs to prepare:

- `profileId=target-form`
- `targetUrl=https://app.example.test`

Expected prepare evidence:

- response starts with `Guided auth session prepared.`
- response includes `Auth Profile: target-form`
- response includes `Auth Kind: form`
- response includes `Authorized Origin: https://app.example.test`
- response includes `Provider: zap-form-login`
- response includes `Engine Binding: ZAP context/user ready`
- response includes `Context ID:` and `User ID:`
- response includes a `Session ID:` to validate
- response does not include the secret value

Expected validate evidence:

- response starts with `Guided auth session validation complete.`
- response includes `Valid: true`
- response includes `Outcome: authenticated`
- diagnostics include `likelyAuthenticated=true`
- diagnostics include the same `contextId` and `userId`
- response does not include the secret value

If validation returns `Outcome: authentication_failed`, stop. Fix credentials,
login URL, field names, and indicators before running authenticated crawl or
attack. Continuing would produce evidence that looks official but is not
actually authenticated.

## Bearer And API-Key Prepare Failures

Bearer and API-key profiles currently prepare a gateway credential-reference
session. It does not prove the target accepts that credential, and it does not
automatically inject the header into current guided ZAP execution.

Expected validation behavior:

- `reference_valid` means the profile's credential reference resolves

For production-like use:

- configure an exact `credential-reference=env:NAME` or
  `credential-reference=file:/absolute/path` in the profile
- verify the secret is present in the MCP container or pod
- verify `allowed-origin` is the exact relying origin
- do not present header-based bootstrap as authenticated scan execution until
  header injection is implemented in the engine path

## Guided Scan Failures With `authSessionId`

Choose a profile kind that matches the operation:

| Operation | Required profile kind | Supported strategy |
| --- | --- | --- |
| `zap_crawl_start` with Client Spider | `browser` | `client` (direct or queued) |
| `zap_crawl_start` with traditional spider | `form` | `http`; authenticated `auto` uses HTTP |
| `zap_attack_start` | `form` | Guided active scan |

AJAX Spider (`strategy=browser`) does not accept `authSessionId`. A browser
profile supports Client Spider only; do not reuse it for a guided active scan.
See the [Client Spider guide](https://danieltse.org/mcp-zap-server/scanning/client-spider/).

If an authenticated guided operation fails:

1. Validate the session with `zap_auth_session_validate` and require `Valid: true`.
2. Confirm `Auth Kind` matches the operation in the table.
3. Confirm the session reports `Engine Binding: ZAP context/user ready`.
4. For a browser profile, use a protected validation target whose response proves
   login and confirm fresh crawl requests reach authenticated content.
5. Re-run prepare if the session ID is unknown or stale.

If validation is failing, the scan is not the problem. Fix auth first.

## Operator Evidence

For every auth bootstrap incident, capture:

- request `correlationId`
- MCP client ID and workspace ID
- tool name and request ID
- SHA-256 fingerprint of the session ID, if one was created; never capture the raw session ID
- validation outcome and diagnostics
- relevant MCP service logs around the same `correlationId`
- relevant ZAP logs for context/user authentication attempts

Do not capture raw secrets or exact credential references in caller-visible errors,
tickets, screenshots, or support notes. Exact environment names and mounted paths
belong only in restricted operator logs. Secret values must never be logged.

## Escalation Rules

Escalate as product work, not operator configuration, when:

- the target requires MFA, SSO, CAPTCHA, or custom login steps beyond the supported browser profile
- the user needs authenticated AJAX crawl (`strategy=browser`)
- bearer/API-key header injection is required for guided execution
- multiple customers hit the same unsupported auth pattern

Escalate as deployment work when:

- profile env/file credential references do not resolve in the runtime
- secret mounts differ between MCP and ZAP containers
- ingress, proxy, or target allowlist rules block the login flow

Escalate as target-specific setup when:

- login field names are wrong
- success/failure indicators are too broad
- the target invalidates sessions quickly
- the scan user lacks access to the paths being tested

## Done Criteria

An auth bootstrap failure is resolved only when:

- `zap_auth_session_prepare` returns the expected session type
- `zap_auth_session_validate` returns a valid outcome for form-login sessions,
  or `reference_valid` for header credential-reference sessions
- the operator can explain the failure cause without reading source code
- no raw secret was exposed during debugging
- the next scan result is labeled honestly according to the support boundary
