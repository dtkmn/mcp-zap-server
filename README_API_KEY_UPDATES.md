## Configuring ZAP API Key (Recommended Security Practice)

For enhanced security, it is highly recommended to configure OWASP ZAP to use an API key. This prevents unauthorized access to the ZAP API. The `mcp-zap-server` and ZAP itself need to be configured with the same API key.

By default, this setup now requires an API key. If `ZAP_API_KEY_VALUE` is not set, ZAP might not start correctly or `mcp-server` might fail to connect.

### 1. Generate a Strong API Key

You can generate a strong, random API key using tools like OpenSSL:
```bash
openssl rand -hex 16
```
This will output a random 32-character hexadecimal string, for example: `abcdef1234567890abcdef1234567890`. Copy this value.

Alternatively, if you start ZAP without `api.disablekey=true` and without specifying an `api.key` through a command-line option, ZAP will generate a random API key on its first run. You would typically find this key in ZAP's console output or log files. However, for reproducible `docker-compose` deployments, explicitly setting a key is more reliable.

### 2. Configure the API Key

You need to make this API key available as an environment variable, which will then be used by both the `zap` and `mcp-server` services in the `docker-compose.yml` file. The recommended way is to use an `.env` file in the root of the `mcp-zap-server` project directory.

Create a file named `.env` (if it doesn't already exist) and add the following line, replacing `your_generated_api_key_here` with the key you generated:

```env
# .env file
ZAP_API_KEY_VALUE=your_generated_api_key_here
```

**Example `.env` file:**
```env
# .env file
ZAP_API_KEY_VALUE=abcdef1234567890abcdef1234567890
LOCAL_ZAP_WORKPLACE_FOLDER=$(pwd)/zap-workplace # Example, keep other vars if they exist
```

### 3. How it Works in `docker-compose.yml`

The `docker-compose.yml` file is configured to use the `ZAP_API_KEY_VALUE` environment variable:

*   **For the `zap` service:**
    *   The command-line option `-config api.disablekey=true` has been removed.
    *   A new option `-config api.key=${ZAP_API_KEY_CONFIGURED}` is used.
    *   The `environment` section for the `zap` service sets `ZAP_API_KEY_CONFIGURED: ${ZAP_API_KEY_VALUE:-}`. This means ZAP will start with the API key you provided.

*   **For the `mcp-server` service:**
    *   The `environment` section for `mcp-server` sets `ZAP_API_KEY: ${ZAP_API_KEY_VALUE:-}`.
    *   The `mcp-server` application reads this `ZAP_API_KEY` (via `application.yml`) and uses it to authenticate its requests to the ZAP API.

### Quick Start (With API Key)

If you are following the Quick Start guide, ensure you create the `.env` file with your `ZAP_API_KEY_VALUE` *before* running `docker-compose up -d`.

```bash
git clone https://github.com/dtkmn/mcp-zap-server.git
cd mcp-zap-server

# Create or edit .env file in this directory
echo "ZAP_API_KEY_VALUE=$(openssl rand -hex 16)" > .env
echo "LOCAL_ZAP_WORKPLACE_FOLDER=$(pwd)/zap-workplace" >> .env # Add other variables as needed

# Now start the services
docker-compose up -d
```

This ensures that ZAP starts with API key protection enabled and that `mcp-server` can securely communicate with it.
If you don't set `ZAP_API_KEY_VALUE`, both `ZAP_API_KEY_CONFIGURED` and `ZAP_API_KEY` will default to an empty string. This might cause ZAP to fail to start with an enforced API key policy or `mcp-server` to be unable to connect if ZAP generates its own key. **Always set `ZAP_API_KEY_VALUE` for proper operation.**

---

*(This new section should be integrated into the main `README.md`, perhaps before or as part of the "Quick Start" section, and existing parts of the README mentioning ZAP_API_KEY should be updated to reflect these changes.)*
