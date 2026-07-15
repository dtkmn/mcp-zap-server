# Auth Bootstrap Failure Runbook

Use this runbook when `zap_auth_session_prepare`, `zap_auth_session_validate`,
or an authenticated guided scan fails.

This is a triage guide, not a sales shield. If a flow is unsupported, say it is
unsupported. Do not burn operator time pretending configuration will fix a
capability gap.

## Current Support Boundary

Supported in the current gateway window:

- form-login bootstrap backed by ZAP context and user configuration
- bearer/API-key profile preparation at the gateway layer
- guided `zap_auth_session_prepare` and `zap_auth_session_validate`
- authenticated guided crawl/attack with prepared form-login sessions

Important caveat:

- bearer/API-key sessions validate their configured credential references, but current guided ZAP
  flows do not automatically inject those headers into engine execution yet

Unsupported today:

- browser/AJAX guided crawl with `authSessionId`
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
| `form-login sessions only` | Header session passed to guided scan | Use a form-login session or omit `authSessionId`. |
| Browser strategy rejects auth | Authenticated browser crawl is unsupported today | Use HTTP guided crawl with form-login, or treat AJAX authenticated crawl as future work. |

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

If `likelyAuthenticated` is anything other than `true`, do not continue to an
authenticated scan and pretend the result is meaningful.

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

Authenticated guided crawl/attack currently accepts prepared form-login
sessions only.

If `zap_crawl_start` or `zap_attack_start` fails after adding `authSessionId`:

1. Validate the session first with `zap_auth_session_validate`.
2. Confirm the session reports `Auth Kind: form`.
3. Confirm the session reports `Engine Binding: ZAP context/user ready`.
4. For crawl, use the HTTP strategy. Browser/AJAX strategy with auth is not
   supported in this window.
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

- the target requires MFA, SSO, CAPTCHA, or JavaScript-heavy login automation
- the user needs authenticated AJAX/browser crawl
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
