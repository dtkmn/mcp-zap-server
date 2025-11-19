# AJAX Spider - Bypassing WAF and Scanning JavaScript Applications

The AJAX Spider uses a **real browser** (Firefox headless via Selenium) to crawl websites. This is much more effective than the traditional HTTP-based spider for:

## Use Cases

### 1. **Bypass WAF Protection** 🛡️
Sites like `endclothing.com` block automated tools but allow browsers:
- Real browser User-Agent
- JavaScript execution
- Cookies and sessions handled automatically
- Renders like a normal user visit

### 2. **JavaScript-Heavy Applications** ⚛️
Modern SPAs built with React, Angular, Vue:
- Executes JavaScript to render content
- Handles dynamic page updates
- Discovers AJAX endpoints
- Crawls client-side routing

### 3. **Bot Detection Bypass** 🤖
Sites with Cloudflare, Akamai, or custom bot detection:
- Full browser fingerprint
- Realistic user behavior simulation
- Passes JavaScript challenges
- Handles CAPTCHAs better (when configured)

## Available Tools

### 1. Start AJAX Spider
```
Tool: zap_ajax_spider
Description: Start an AJAX Spider scan using a real browser
Parameter: targetUrl (e.g., https://www.endclothing.com/au)

Example:
"Use zap_ajax_spider to scan https://www.endclothing.com/au"
```

### 2. Check Status
```
Tool: zap_ajax_spider_status
Description: Get current status of AJAX Spider scan

Returns:
- Scan status (running/stopped)
- Number of URLs discovered
- Progress information
```

### 3. Get Results
```
Tool: zap_ajax_spider_results
Description: Get all discovered URLs and full scan results
```

### 4. Stop Scan
```
Tool: zap_ajax_spider_stop
Description: Stop the currently running AJAX Spider scan
```

## Configuration

In `docker-compose.yml`, AJAX Spider is automatically installed:
```yaml
command:
  - -addoninstall
  - ajaxSpider
```

Settings (in `application.yml`):
```yaml
zap:
  scan:
    limits:
      maxSpiderScanDurationInMins: 15  # Used by AJAX Spider
```

## Performance Comparison

| Spider Type | Speed | WAF Bypass | JavaScript | Resource Usage |
|-------------|-------|------------|------------|----------------|
| **Traditional Spider** | Fast | ❌ Poor | ❌ No | Low (HTTP only) |
| **AJAX Spider** | Slower | ✅ Excellent | ✅ Full | High (Firefox + Selenium) |

## Usage Example

### Scanning a Protected Site

```bash
# Traditional spider fails with socket exception
zap_spider https://www.endclothing.com/au
# ❌ Error: SocketException - site blocks ZAP

# AJAX Spider succeeds with real browser
zap_ajax_spider https://www.endclothing.com/au
# ✅ Success: Crawls like a normal user
```

### Monitoring Progress

```bash
# Check status every 30 seconds
zap_ajax_spider_status
# Output: "Status: running, Pages discovered: 45"

# Get full results when done
zap_ajax_spider_results
# Output: All discovered URLs, forms, and endpoints
```

## Technical Details

### Browser Configuration
- **Type**: Firefox Headless
- **Instances**: 2 parallel browsers (configurable)
- **Duration**: 15 minutes max (configurable)
- **User-Agent**: Chrome 131 (realistic modern browser)

### What Gets Discovered
- All URLs loaded via JavaScript
- AJAX/Fetch API endpoints
- WebSocket connections
- Single Page Application routes
- Dynamically rendered content
- Form submissions

### Resource Requirements
```yaml
zap:
  environment:
    ZAP_MEMORY: 4G  # AJAX Spider needs more RAM
```

## Troubleshooting

### AJAX Spider Not Available
```
Error: AJAX Spider addon is not available
Solution: Restart containers (addon installs on first start)
```

### Slow Scanning
```
Issue: AJAX Spider is slower than traditional spider
Solution: This is expected - real browser emulation takes time
Configure: Reduce maxSpiderScanDurationInMins if needed
```

### High Memory Usage
```
Issue: ZAP uses 2-4GB RAM during AJAX scans
Solution: Increase ZAP_MEMORY environment variable
```

## Best Practices

1. **Start with AJAX Spider** for protected sites
2. **Use Traditional Spider** for simple sites or when speed matters
3. **Combine both** for comprehensive coverage:
   ```
   1. Run traditional spider first (fast baseline)
   2. Run AJAX spider second (discover JS content)
   ```
4. **Monitor resources** - AJAX Spider is memory-intensive
5. **Test with known targets** first (juice-shop, petstore)

## Integration with Cursor/Claude

In your Cursor chat:

```
"Scan https://www.endclothing.com/au using AJAX Spider to bypass WAF"

"Check the AJAX Spider status"

"Get all URLs discovered by AJAX Spider"

"Stop the AJAX Spider scan"
```

The AI will automatically use the appropriate ZAP tool! 🚀

## References

- [OWASP ZAP AJAX Spider Documentation](https://www.zaproxy.org/docs/desktop/addons/ajax-spider/)
- [Selenium WebDriver](https://www.selenium.dev/)
- [WAF Bypass Techniques](https://owasp.org/www-community/Web_Application_Firewall)
