---
title: "Optional: Form-Login Target Authentication"
editUrl: false
description: "Configure a website login for ZAP, validate it from Cursor, and use it with the guided HTTP crawl and active scan paths."
---

Skip this guide unless the application you are authorized to scan requires a
straightforward username/password form login. Public pages and unauthenticated
APIs work without any auth profile.

## First, Separate The Two Authentication Layers

| Credential | What it authenticates | Where it belongs |
| --- | --- | --- |
| MCP API key or JWT | Cursor to MCP ZAP Server | Cursor's `X-API-Key` or `Authorization` header |
| Website username/password | ZAP to the authorized target | A server-side auth profile and mounted secret |

These layers are independent. JWT remains fully valid for MCP access; it does
not log ZAP in to the website. Never put the target website password in Cursor,
`mcp.json`, a prompt, or a tool argument. Cursor supplies only a profile ID and
target URL.

ZAP provides the form-login engine, contexts, users, session handling, and
scan-as-user capabilities. MCP ZAP Server securely configures and orchestrates
those ZAP features through an operator-managed profile. This project did not
build a second login engine from scratch.

## What Works Today

| Target authentication | Prepare and validate | Guided crawl and attack use it |
| --- | --- | --- |
| Traditional HTML username/password form | Yes | Yes, with the HTTP spider and active scan |
| Target bearer token | Credential reference only | No automatic header injection yet |
| Target API key | Credential reference only | No automatic header injection yet |
| OAuth, SSO, MFA, CAPTCHA, or browser-only login | No | No |

The guided form provider is for a classic URL-encoded form with predictable
username and password fields. Dynamic CSRF tokens, multi-step flows, JSON login
APIs, and JavaScript-heavy single-page applications may require a future
browser/client-script provider or ZAP's expert controls. ZAP itself recommends
[browser-based or client-script authentication for modern flows](https://www.zaproxy.org/blog/2025-07-03-authentication-improvements/).

The profile workflow is available in `v0.10.0` and later. `v0.9.1` and earlier
do not contain this contract.

## Before You Start

Complete [Self-Serve First Run](../self-serve-first-run/) first. Confirm that
Cursor can list the guided ZAP tools using the MCP API key or JWT you chose.

For the target application, gather:

| Value | Example | Rule |
| --- | --- | --- |
| Profile ID | `local-form-app` | A stable label using letters, numbers, dots, underscores, or dashes |
| Allowed origin | `http://host.docker.internal:8080` | Scheme, host, and optional port only; no path |
| Target URL | `http://host.docker.internal:8080/account` | Must use exactly the same origin |
| Login URL | `http://host.docker.internal:8080/login` | Must use exactly the same origin |
| Scan username | `dedicated-zap-scan-user` | Use a dedicated, least-privilege test account |
| Username field | `username` | The form input's HTML `name`, not its label or `id` |
| Password field | `password` | The form input's HTML `name`, not its label or `id` |
| Logged-in indicator | `Logout` | Stable response text present only after successful login |
| Logged-out indicator | `Sign in` | Optional stable response text that proves login was lost |

For example, this form uses `email` and `password` as the field names:

```html
<form action="/login" method="post">
  <input name="email" type="email">
  <input name="password" type="password">
</form>
```

Choose the URL that the containers can reach:

| Where the target runs | Use in the profile and Cursor |
| --- | --- |
| On your laptop, port 8080 | `http://host.docker.internal:8080` |
| In the same Compose project as service `my-app`, port 8080 | `http://my-app:8080` |
| On an authorized remote host | Its real `https://...` origin |

`localhost` inside MCP Server or ZAP means that container, not your laptop.
On native Linux, `host.docker.internal` resolves through the Docker gateway,
but it cannot reach a target that listens only on host `127.0.0.1`. Bind the
target to a Docker-reachable host interface and use firewall rules to keep that
listener off untrusted networks.

## 1. Create The Non-Secret Profile

From the repository root:

```bash
AUTH_HOME="${XDG_CONFIG_HOME:-$HOME/.config}/mcp-zap-server/auth"
install -d -m 700 "$AUTH_HOME"
cp examples/authenticated-scanning/auth-profiles.example.yml \
  "$AUTH_HOME/auth-profiles.yml"
```

Edit `$AUTH_HOME/auth-profiles.yml` and replace every target-specific value.
The checked-in example describes an application running on your laptop at port
8080. It is not a bundled demo target.

When editing the profile, remember:

- `allowed-origin` has no path and must exactly match the scheme, host, and port
  of both `login-url` and the `targetUrl` you will use in Cursor.
- Do not add a terminal dot to a hostname.
- Keep `credential-reference` set to
  `file:/run/secrets/target_form_password` for this Compose example.
- The logged-in indicator must be visible in the response ZAP receives, not
  text inserted later only by browser JavaScript.

Make the final non-secret configuration readable by the container but not
writable:

```bash
chmod 0444 "$AUTH_HOME/auth-profiles.yml"
```

## 2. Create The Password File

The following reads the password without echoing it or putting it in shell
history:

```bash
AUTH_HOME="${XDG_CONFIG_HOME:-$HOME/.config}/mcp-zap-server/auth"
printf 'Target test-account password: ' >&2
IFS= read -r -s TARGET_FORM_PASSWORD
printf '\n' >&2
if [ -z "$TARGET_FORM_PASSWORD" ]; then
  printf 'Password cannot be empty; no secret file was written.\n' >&2
  unset TARGET_FORM_PASSWORD
else
  (umask 077; printf '%s' "$TARGET_FORM_PASSWORD" > "$AUTH_HOME/target-form-password")
  unset TARGET_FORM_PASSWORD
  chmod 0444 "$AUTH_HOME/target-form-password"
fi
```

The credential resolver trims leading and trailing whitespace. A target
password that intentionally begins or ends with whitespace is not supported by
this profile path.

The `0444` file mode is intentional for the default local Compose path. The
containing directory is `0700`, while the individual bind-mounted files must be
readable by the non-root container user (UID/GID 1000). Compose file-backed
secrets do not remap host file ownership. See Docker's
[Compose secrets reference](https://docs.docker.com/reference/compose-file/services/#secrets).

## 3. Point Compose At The Files

Add these two entries to the repository's ignored `.env` file, using fully
expanded absolute paths from your machine:

```dotenv
MCP_ZAP_AUTH_PROFILES_FILE=/Users/you/.config/mcp-zap-server/auth/auth-profiles.yml
MCP_ZAP_FORM_PASSWORD_FILE=/Users/you/.config/mcp-zap-server/auth/target-form-password
```

Do not use `~` or `$HOME` in `.env`; Compose does not recursively shell-expand
those values. Keep `.env` private because it already contains the MCP and ZAP
API keys:

```bash
chmod 0600 .env
```

## 4. Render And Start The Optional Setup

Always use the same three Compose files while target authentication is enabled:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  config --quiet

docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  up -d --build --force-recreate --wait
```

The override adds `host.docker.internal` support to both MCP Server and ZAP,
mounts the non-secret profile, and mounts the password as a Compose secret.

Do not run `./dev.sh` while this optional profile is enabled. That script does
not load the override and can recreate `mcp-server` without the profile.

## 5. Check The Wiring Without Printing Secrets

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  exec -T mcp-server sh -c \
  'test -r /app/auth-profiles.yml &&
   test -r /run/secrets/target_form_password &&
   test -s /run/secrets/target_form_password'

docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  exec -T zap curl -fsS -o /dev/null \
  http://host.docker.internal:8080/login

./bin/self-serve-doctor.sh
```

The doctor proves MCP and ZAP connectivity. It does not prove the target login.
Profiles are loaded when MCP Server starts, so a health check alone is not
evidence that the profile or password is correct.

Replace the preflight login URL if your target is not the host-port example. A
successful GET proves only that ZAP can reach the login route; Cursor's prepare
and validate flow still has to prove authentication.

## 6. Prepare And Validate From Cursor

Give Cursor this prompt, replacing the profile and target URL if you changed
them:

```text
Use only the guided ZAP tools.

Prepare target form-login authentication with profile `local-form-app` for
http://host.docker.internal:8080/account.

Validate the returned auth session. Stop and report the diagnostics unless the
result contains all three lines:
Valid: true
Outcome: authenticated
likelyAuthenticated=true

If validation succeeds, start an HTTP crawl against the same origin using the
returned authSessionId. Poll until complete, wait for passive analysis, and
summarize the findings. Do not start an active scan.

Never ask for or echo the target website password.
```

Preparation should also report:

```text
Provider: zap-form-login
Engine Binding: ZAP context/user ready
```

Treat anything other than `likelyAuthenticated=true` as failure. Validation is
a required operator/agent workflow step; scan start does not currently enforce
that you called validation first.

After the crawl, confirm at least one page that genuinely requires login was
reached. A green login check plus public-only crawl coverage is not enough to
claim an authenticated scan.

Treat the returned auth session ID as a sensitive, process-local capability.
Do not paste it into tickets or logs. It disappears when MCP Server restarts.

## 7. Optionally Approve An Active Scan

Active scanning can change data, send destructive inputs, or trigger side
effects. It is never implied by this setup. When the target owner has approved
it, use a separate prompt:

```text
Using the already prepared and validated form-login auth session, start a
guided active scan against http://host.docker.internal:8080/account. Use the same
authSessionId and exact origin. Poll until complete, wait for passive analysis,
then summarize findings. Stop if the auth session is missing or invalid.
```

The authorized target may be local, in a private cloud, or in Kubernetes. MCP
ZAP Server does not require anyone to call it "staging" or "production".

The guided ZAP context covers the profile's entire allowed origin, even if the
Cursor target URL contains a narrower path. Only the origin's root `/logout`
path is excluded automatically. If the application uses another logout route
or has destructive account-management endpoints, do not approve an active
scan until those paths are controlled through target-side safeguards or an
expert ZAP context.

## Changing Or Removing The Profile

Profile configuration, password rotation, and prepared sessions are process
state. After changing the profile or password, recreate `mcp-server`, then
prepare and validate a new session.

The example files are read-only after setup. For a rotation, temporarily make
them writable, update the profile and/or replace the password using Steps 1 and
2, restore the read-only mode, and recreate only MCP Server:

```bash
AUTH_HOME="${XDG_CONFIG_HOME:-$HOME/.config}/mcp-zap-server/auth"
chmod 0600 "$AUTH_HOME/auth-profiles.yml" "$AUTH_HOME/target-form-password"
# Edit auth-profiles.yml and/or repeat the password-entry block from Step 2.
chmod 0444 "$AUTH_HOME/auth-profiles.yml" "$AUTH_HOME/target-form-password"

docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  up -d --force-recreate --no-deps --wait mcp-server
```

To return to the normal unauthenticated-target quick start:

```bash
docker compose \
  -f docker-compose.yml \
  -f docker-compose.dev.yml \
  -f examples/authenticated-scanning/docker-compose.auth-profile.yml \
  down
./dev.sh
```

Your MCP API key or JWT setup does not change either way.

## Troubleshooting

| Symptom | Meaning | Fix |
| --- | --- | --- |
| Cursor gets `401 Unauthorized` | MCP access authentication failed | Fix Cursor's `X-API-Key` or bearer token; this is not a target-login problem |
| Cursor gets `403 Forbidden` on an auth or scan tool | The MCP principal lacks the required tool scope | Grant the appropriate auth/scan scope or use the default local API-key client |
| `Unknown auth profile ID` | The profile is absent or MCP Server was not recreated | Check the mounted file and restart with all three Compose files |
| `Auth profile credential could not be resolved` | The secret is missing or unreadable inside the container | Run the non-secret readability check and verify the absolute host path |
| Target origin is not authorized | Scheme, host, or port differs | Make `allowed-origin`, `login-url`, and Cursor's target URL use the same exact origin |
| `authentication_failed` | Credentials, form fields, indicators, or the auth flow are wrong | Verify the test account and inspect the actual form `name` attributes and response text |
| Browser strategy rejects auth | Guided authenticated crawl supports HTTP spider only | Use `strategy: http`; do not use AJAX/browser strategy with `authSessionId` |
| Session becomes unknown after restart | Prepared sessions are held in memory | Prepare and validate a new session |
| Header profile validates but the crawl is anonymous | Target bearer/API-key injection is not implemented | Do not use header profiles as authenticated scan evidence |

If the login uses OAuth, SSO, MFA, CAPTCHA, dynamic CSRF handling, or a
JavaScript/JSON flow, stop forcing it through this provider. That is an
unsupported flow, not a documentation typo.

## Next Reading

- [MCP Client Authentication](../mcp-client-authentication/) for Cursor API-key or JWT setup
- [MCP Client Scan To Evidence](../../scanning/mcp-client-scan-to-evidence/) for the full guided workflow
- [Authenticated Scanning Reference](../../scanning/authenticated-scanning-best-practices/) for advanced deployment and expert ZAP controls
- [ZAP authentication concepts](https://www.zaproxy.org/docs/getting-further/authentication/concepts/) for the underlying engine model
