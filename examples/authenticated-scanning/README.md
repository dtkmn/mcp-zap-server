# Optional Form-Login Target Authentication

These templates add one operator-managed form-login profile to the local
Docker Compose stack. Use them only when an application you are authorized to
scan requires a straightforward username/password HTML form.

This is target authentication: ZAP logs in to the application. It is separate
from the API key or JWT that Cursor uses to access MCP ZAP Server.

The example assumes the target runs on the host at
`http://host.docker.internal:8080`. It is a template, not a bundled test
application. Copy `auth-profiles.example.yml` outside the repository and edit:

- the exact origin and login URL
- the dedicated scan username
- the form fields' HTML `name` attributes
- stable logged-in and logged-out indicators

Keep the password in a separate host file. Add the two fully expanded absolute
paths to the ignored root `.env` file:

```dotenv
MCP_ZAP_AUTH_PROFILES_FILE=/absolute/path/to/auth-profiles.yml
MCP_ZAP_FORM_PASSWORD_FILE=/absolute/path/to/target-form-password
```

Then use the same three Compose files whenever this profile is enabled:

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

`./dev.sh` does not load the optional override. Do not use it while this profile
is enabled, because it can recreate `mcp-server` without the profile.

For the complete setup, Cursor prompt, success criteria, networking cases, and
troubleshooting, read the
[Form-Login Target Authentication guide](https://danieltse.org/mcp-zap-server/getting-started/form-login-target-authentication/).
