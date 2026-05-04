# Auth Bootstrap Failure Runbook

Use this runbook when `zap_auth_session_prepare`, `zap_auth_session_validate`,
or an authenticated guided scan fails.

This is a triage guide, not a sales shield. If a flow is unsupported, say it is
unsupported. Do not burn operator time pretending configuration will fix a
capability gap.

## Current Support Boundary

Supported in the current gateway window:

- form-login bootstrap backed by ZAP context and user configuration
- bearer/API-key credential-reference preparation at the gateway layer
- guided `zap_auth_session_prepare` and `zap_auth_session_validate`
- authenticated guided crawl/attack with prepared form-login sessions

Important caveat:

- bearer/API-key sessions validate credential references, but current guided ZAP
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
  `zap_crawl`, or `zap_attack`
- `authKind`
- `targetUrl`
- whether the session reports `Engine Binding: ZAP context/user ready` or
  `Engine Binding: gateway contract only`
- the exact `Outcome` and `Diagnostics` lines from validation

Then classify the failure:

| Symptom | Likely Cause | First Action |
| --- | --- | --- |
| `authKind must be one of: form, bearer, api-key` | Unsupported or misspelled auth kind | Use `form`, `bearer`, or `api-key` only. |
| `targetUrl cannot be null or blank` | Missing target URL | Supply the target host or base URL. |
| `loginUrl cannot be null or blank` | Form login request is incomplete | Supply the actual login form URL. |
| `username cannot be null or blank` | Form login username missing | Supply the login username. |
| `loggedInIndicatorRegex cannot be null or blank` | No success indicator for validation | Provide a regex that appears only after login. |
| `Provide credentialReference or inlineSecret` | No secret source | Prefer `credentialReference`. |
| `inlineSecret is disabled by default` | Inline secret used outside local mode | Use `env:NAME` or `file:/absolute/path`, or explicitly enable inline secrets only for local workflows. |
| `credentialReference is not in the operator allowlist` | Caller selected an env var or file path that operators did not pre-approve | Add the exact `env:NAME` or `file:/absolute/path` to `MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES`, or reject the workflow. |
| `Environment variable ... is missing or blank` | Secret env var unavailable to the MCP process | Fix the deployment secret/env injection. |
| `credentialReference file path must be absolute` | Relative secret path | Use `file:/absolute/path`. |
| `Unable to read credentialReference file` | File missing or unreadable | Fix mount path and file permissions for the MCP runtime. |
| `reference_missing` | Header session was prepared from inline secret | Re-prepare with `credentialReference`. |
| `authentication_failed` | ZAP could not authenticate the configured user | Check credentials, login URL, field names, and login indicators. |
| `loginUrl must share the same origin as targetUrl` | Form login points to a different scheme, host, or port than the scan target | Keep login and target on the same origin; do not use auth bootstrap for cross-origin credential forwarding. |
| `usernameField contains unsupported characters` or `passwordField contains unsupported characters` | Form field name could inject extra ZAP auth config parameters | Use simple field names containing letters, numbers, dot, underscore, dash, colon, or brackets. |
| `Unknown auth session ID` | Session ID lost or wrong | Re-run `zap_auth_session_prepare`. |
| `form-login sessions only` | Header session passed to guided scan | Use a form-login session or omit `authSessionId`. |
| Browser strategy rejects auth | Authenticated browser crawl is unsupported today | Use HTTP guided crawl with form-login, or treat AJAX authenticated crawl as future work. |

## Form-Login Prepare Failures

Form-login preparation needs all of this:

- `targetUrl`
- `loginUrl`
- `username`
- operator-allowlisted `credentialReference` or allowed local `inlineSecret`
- `loggedInIndicatorRegex`

Optional but often necessary:

- `contextName`
- `userName`
- `usernameField`
- `passwordField`
- `loggedOutIndicatorRegex`

If preparation fails before a session ID is returned, fix input or secret
resolution first. There is no session to validate yet.

If preparation returns a session but validation fails:

1. Confirm the credential source resolves inside the MCP runtime, not only on
   the operator laptop.
2. Confirm `loginUrl` is the form POST entrypoint ZAP can use.
3. Confirm `usernameField` and `passwordField` match the form field names.
4. Confirm `loggedInIndicatorRegex` appears only after successful login.
5. Confirm `loggedOutIndicatorRegex`, if supplied, does not also match
   authenticated pages.
6. Use lower-level context/user tools only after the guided request is known to
   be structurally correct.

Validation output should include:

- `likelyAuthenticated=...`
- `contextId=...`
- `userId=...`

If `likelyAuthenticated=false`, do not continue to an authenticated scan and
pretend the result is meaningful.

## Staging Checklist: Form-Login Prepare And Validate

Use this checklist before calling an authenticated pilot ready. It is for
staging/manual validation of form-login prepare and validate behavior, not for
capturing real credentials in docs or tickets.

Inputs to prepare:

- `targetUrl=https://staging.example.test`
- `authKind=form`
- `credentialReference=env:STAGING_SCAN_PASSWORD` or
  `credentialReference=file:/absolute/path/to/mounted/secret`
- `MCP_AUTH_BOOTSTRAP_ALLOWED_CREDENTIAL_REFERENCES` includes the exact
  reference, or an operator-approved prefix such as `env:STAGING_SCAN_*` or
  `file:/var/run/secrets/mcp-zap/*`
- file wildcards are directory containment rules; sibling-prefix paths and
  symlink escapes are intentionally rejected
- `sessionLabel=staging-form-auth`
- `contextName=staging-form-auth`
- `loginUrl=https://staging.example.test/login`
- `username=<scan-user-name>`
- `usernameField=<login-form-username-field>`
- `passwordField=<login-form-password-field>`
- field names may contain letters, numbers, dot, underscore, dash, colon, or
  brackets only; ZAP auth config values are URL-encoded by the server
- `loggedInIndicatorRegex=<text-only-visible-after-login>`
- optional `loggedOutIndicatorRegex=<text-only-visible-before-login>`

Expected prepare evidence:

- response starts with `Guided auth session prepared.`
- response includes `Auth Kind: form`
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

Bearer and API-key bootstrap currently prepares a gateway credential-reference
session. It does not prove the target accepts that credential, and it does not
automatically inject the header into current guided ZAP execution.

Expected validation behavior:

- `reference_valid` means the credential reference resolves
- `reference_missing` means the session was prepared from inline secret and
  cannot be revalidated later

For production-like use:

- use `credentialReference=env:NAME` or `credentialReference=file:/absolute/path`
- verify the secret is present in the MCP container or pod
- verify the reference is allowlisted by operators
- do not rely on inline secrets for repeatable operator workflows
- do not present header-based bootstrap as authenticated scan execution until
  header injection is implemented in the engine path

## Guided Scan Failures With `authSessionId`

Authenticated guided crawl/attack currently accepts prepared form-login
sessions only.

If `zap_crawl` or `zap_attack` fails after adding `authSessionId`:

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
- session ID, if one was created
- validation outcome and diagnostics
- relevant MCP service logs around the same `correlationId`
- relevant ZAP logs for context/user authentication attempts

Do not capture raw secrets in tickets, logs, screenshots, or support notes.
Credential references are acceptable; secret values are not.

## Escalation Rules

Escalate as product work, not operator configuration, when:

- the target requires MFA, SSO, CAPTCHA, or JavaScript-heavy login automation
- the user needs authenticated AJAX/browser crawl
- bearer/API-key header injection is required for guided execution
- multiple customers hit the same unsupported auth pattern

Escalate as deployment work when:

- env/file credential references do not resolve in the runtime
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
