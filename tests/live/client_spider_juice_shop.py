#!/usr/bin/env python3
"""Opt-in live Client Spider regression checks against an isolated Juice Shop.

Python standard library only. This creates and cancels browser crawls; use a
dedicated ZAP session and the authorized local lab, never a shared production ZAP.
See README.md in this directory for setup and the exact scope of these checks.
"""

import argparse
import datetime as dt
import json
import os
from pathlib import Path
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


def timestamp():
    return dt.datetime.now(dt.timezone.utc).isoformat()


def field(text, label):
    match = re.search(r"^" + re.escape(label) + r":[ \t]*([^\r\n]+)", text, re.M)
    if not match or not match.group(1).strip():
        raise AssertionError(f"Missing {label!r} in response: {text}")
    return match.group(1).strip()


def requested_resources(messages, target):
    """Count distinct resource paths from this crawl, ignoring query variations."""
    resources = set()
    for message in messages:
        lines = message.get("requestHeader", "").splitlines()
        parts = lines[0].split() if lines else []
        if len(parts) >= 2:
            url = urllib.parse.urlparse(urllib.parse.urljoin(target, parts[1]))
            if url.scheme in {"http", "https"} and url.netloc:
                resources.add(urllib.parse.urlunparse((url.scheme, url.netloc, url.path or "/", "", "", "")))
    return resources


def progress(text):
    return int(field(text, "Progress").rstrip("%"))


def content_text(result):
    items = []
    for item in result.get("content", []):
        if item.get("type") != "text":
            continue
        value = item.get("text", "")
        # Some Spring AI versions serialize a String return as a JSON string.
        try:
            decoded = json.loads(value)
            if isinstance(decoded, str):
                value = decoded
        except (ValueError, TypeError):
            # Plain tool text is not JSON; preserve its original value.
            pass
        items.append(value)
    return "\n".join(items)


def rpc_response(response, wanted_id):
    """Read JSON or one matching JSON-RPC event without waiting for SSE EOF."""
    if "text/event-stream" not in response.headers.get("Content-Type", ""):
        body = response.read().decode("utf-8")
        return json.loads(body) if body.strip() else None
    data = []
    while True:
        line = response.readline().decode("utf-8")
        if not line:
            raise RuntimeError("SSE closed before the matching JSON-RPC response")
        line = line.rstrip("\r\n")
        if line.startswith("data:"):
            data.append(line[5:].lstrip(" "))
        elif not line and data:
            message = json.loads("\n".join(data))
            data = []
            if message.get("id") == wanted_id:
                return message


class McpClient:
    def __init__(self, url, api_key="", timeout=90, record=None):
        self.url, self.api_key, self.timeout = url, api_key, timeout
        self.session, self.protocol, self.sequence = None, "2025-03-26", 0
        self.record = record or (lambda value: None)

    def rpc(self, method, params=None, notification=False):
        self.sequence += 1
        request_id = self.sequence
        payload = {"jsonrpc": "2.0", "method": method}
        if not notification:
            payload["id"] = request_id
        if params is not None:
            payload["params"] = params
        headers = {"Content-Type": "application/json",
                   "Accept": "application/json, text/event-stream",
                   "MCP-Protocol-Version": self.protocol}
        if self.api_key:
            headers["X-API-Key"] = self.api_key
        if self.session:
            headers["Mcp-Session-Id"] = self.session
        request = urllib.request.Request(self.url, json.dumps(payload).encode(), headers)
        event = {"at": timestamp(), "request": payload}
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                self.session = response.headers.get("Mcp-Session-Id", self.session)
                event["httpStatus"] = response.status
                result = None if notification else rpc_response(response, request_id)
                event["response"] = result
        except urllib.error.HTTPError as error:
            event.update(httpStatus=error.code, response=error.read().decode("utf-8"))
            self.record(event)
            raise RuntimeError(f"MCP HTTP {error.code}: {event['response']}") from error
        self.record(event)
        if result and "error" in result:
            raise RuntimeError(f"JSON-RPC error: {result['error']}")
        return result.get("result") if result else None

    def initialize(self):
        result = self.rpc("initialize", {"protocolVersion": self.protocol,
                          "capabilities": {}, "clientInfo": {
                              "name": "client-spider-juice-shop-live-test", "version": "1.0"}})
        self.protocol = result.get("protocolVersion", self.protocol)
        self.rpc("notifications/initialized", notification=True)
        return result

    def tools(self):
        entries, cursor = [], None
        while True:
            page = self.rpc("tools/list", {"cursor": cursor} if cursor else {})
            entries.extend(page["tools"])
            cursor = page.get("nextCursor")
            if not cursor:
                return {item["name"]: item for item in entries}

    def call(self, name, arguments=None):
        result = self.rpc("tools/call", {"name": name, "arguments": arguments or {}})
        if result.get("isError"):
            raise RuntimeError(f"Tool {name}: {content_text(result)}")
        return content_text(result)


class ZapClient:
    def __init__(self, url, api_key="", host_header=""):
        self.url, self.api_key, self.host_header = url.rstrip("/"), api_key, host_header

    def view(self, component, name, **arguments):
        if self.api_key:
            arguments["apikey"] = self.api_key
        url = f"{self.url}/JSON/{component}/view/{name}/?{urllib.parse.urlencode(arguments)}"
        request = urllib.request.Request(url, headers={"Host": self.host_header} if self.host_header else {})
        with urllib.request.urlopen(request, timeout=60) as response:
            result = json.load(response)
        if "code" in result:
            raise RuntimeError(f"ZAP {component}/{name}: {result}")
        return result

    def messages(self, target):
        messages, start = [], 0
        while True:
            page = self.view("core", "messages", baseurl=target, start=start, count=1000)["messages"]
            messages.extend(page)
            if len(page) < 1000:
                return messages
            start += len(page)


class Suite:
    def __init__(self, args):
        self.args = args
        self.evidence = {"startedAt": timestamp(), "target": args.target,
                         "mode": args.mode, "phases": args.phases.split(","),
                         "checks": [], "rpc": [], "coverage": {}}
        self.mcp = McpClient(args.mcp_url, args.mcp_api_key, record=self.evidence["rpc"].append)
        self.zap = ZapClient(args.zap_url, args.zap_api_key, args.zap_host_header)
        self.direct_ids, self.job_ids, self.guided_ids = set(), set(), set()
        self.auth_session_id = None

    def save(self):
        path = Path(self.args.evidence)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(self.evidence, indent=2) + "\n")

    def check(self, name, condition, detail=None):
        self.evidence["checks"].append({"name": name, "passed": bool(condition), "detail": detail})
        self.save()
        print(f"{'PASS' if condition else 'FAIL'} {name}", flush=True)
        if not condition:
            raise AssertionError(f"{name}: {detail}")

    def rejected(self, name, tool, arguments, expected):
        try:
            response = self.mcp.call(tool, arguments)
        except RuntimeError as error:
            message = str(error)
            self.check(name, expected.lower() in message.lower(), message)
            return
        self.check(name, False, f"Unexpected acceptance: {response}")

    def wait(self, read, predicate, timeout=None):
        deadline = time.monotonic() + (timeout or self.args.timeout)
        while True:
            response = read()
            if predicate(response):
                return response
            if time.monotonic() >= deadline:
                raise AssertionError(f"Timed out waiting for lifecycle transition: {response}")
            time.sleep(self.args.poll_interval)

    def setup(self):
        self.evidence["initialize"] = self.mcp.initialize()
        self.evidence["zapVersion"] = self.zap.view("core", "version")
        self.evidence["installedAddons"] = self.zap.view("autoupdate", "installedAddons")
        self.tools = self.mcp.tools()
        self.evidence["tools"] = self.tools
        expected = (["zap_client_spider_start", "zap_client_spider_status", "zap_client_spider_stop",
                     "zap_queue_client_spider_scan", "zap_scan_job_status", "zap_scan_job_cancel"]
                    if self.args.mode == "expert" else
                    ["zap_crawl_start", "zap_crawl_status", "zap_crawl_stop"])
        expected += ["zap_passive_scan_status", "zap_passive_scan_wait"]
        if self.args.auth_profile:
            expected += ["zap_auth_session_prepare", "zap_auth_session_validate"]
        self.check("required tools published", all(name in self.tools for name in expected), expected)
        if self.args.mode == "expert":
            for tool in ["zap_client_spider_start", "zap_queue_client_spider_scan"]:
                schema = self.tools[tool]["inputSchema"]
                self.check(f"{tool} schema", set(schema.get("required", [])) == {"targetUrl"}
                           and {"targetUrl", "maxDepth", "contextName", "userName"}
                           <= schema.get("properties", {}).keys(), schema)
            for tool in ["zap_client_spider_status", "zap_client_spider_stop"]:
                self.check(f"{tool} requires scanId",
                           self.tools[tool]["inputSchema"].get("required") == ["scanId"])
        else:
            self.check("guided surface hides expert Client Spider tools",
                       "zap_client_spider_start" not in self.tools)
            schema = self.tools["zap_crawl_start"]["inputSchema"]
            self.check("guided crawl schema publishes optional authSessionId",
                       "authSessionId" in schema.get("properties", {})
                       and "authSessionId" not in schema.get("required", []), schema)
        if self.args.auth_profile:
            self.prepare_authentication()

    def prepare_authentication(self):
        prepared = self.mcp.call("zap_auth_session_prepare", {
            "profileId": self.args.auth_profile, "targetUrl": self.args.auth_verification_url})
        self.auth_session_id = field(prepared, "Session ID")
        self.check("prepared profile uses browser authentication", field(prepared, "Auth Kind") == "browser", prepared)
        validation = self.mcp.call("zap_auth_session_validate", {"sessionId": self.auth_session_id})
        self.evidence["authentication"] = {
            "profileId": self.args.auth_profile, "sessionId": self.auth_session_id,
            "verificationUrl": self.args.auth_verification_url,
            "expectedUser": self.args.auth_expected_user,
            "validation": validation}
        self.check("prepared browser authentication validates", field(validation, "Valid").lower() == "true",
                   validation)

    def validation(self):
        if self.args.mode != "expert":
            for name in ["zap_crawl_status", "zap_crawl_stop"]:
                self.rejected(f"{name} rejects blank operation", name, {"operationId": " "}, "operationId")
            return
        for name in ["zap_client_spider_start", "zap_queue_client_spider_scan"]:
            self.rejected(f"{name} rejects negative depth", name,
                          {"targetUrl": self.args.target, "maxDepth": -1}, "maxDepth")
            for missing in ["contextName", "userName"]:
                self.rejected(f"{name} rejects unpaired {missing}", name,
                              {"targetUrl": self.args.target, missing: "nonexistent-test-name"},
                              "contextName and userName")
        for name in ["zap_client_spider_status", "zap_client_spider_stop"]:
            self.rejected(f"{name} rejects blank ID", name, {"scanId": " "}, "scanId")
            self.rejected(f"{name} rejects unknown ID", name,
                          {"scanId": "2147483647"}, "Client Spider")

    def start_direct(self, depth=0):
        response = self.mcp.call("zap_client_spider_start", {"targetUrl": self.args.target, "maxDepth": depth})
        scan_id = field(response, "Scan ID")
        self.direct_ids.add(scan_id)
        return scan_id

    def direct_status(self, scan_id):
        return self.mcp.call("zap_client_spider_status", {"scanId": scan_id})

    def stop_direct(self, scan_id):
        self.mcp.call("zap_client_spider_stop", {"scanId": scan_id})
        result = self.wait(lambda: self.direct_status(scan_id), lambda value: progress(value) == 100)
        self.direct_ids.discard(scan_id)
        return result

    def direct(self):
        first, second = self.start_direct(), self.start_direct()
        self.check("two direct crawls have distinct native IDs", first != second, [first, second])
        self.check("second direct crawl runs before stop", progress(self.direct_status(second)) < 100)
        stopped = self.stop_direct(first)
        self.check("stopped direct status disclaims successful coverage", "stopped scans as well" in stopped, stopped)
        self.check("stopping first leaves second running", progress(self.direct_status(second)) < 100)
        self.stop_direct(second)
        self.check("second direct crawl independently stops", True)

    def job_status(self, job_id):
        return self.mcp.call("zap_scan_job_status", {"jobId": job_id})

    def cancel_job(self, job_id):
        self.mcp.call("zap_scan_job_cancel", {"jobId": job_id})
        result = self.wait(lambda: self.job_status(job_id), lambda value: field(value, "Status") == "CANCELLED")
        self.job_ids.discard(job_id)
        return result

    def queue(self):
        args = {"targetUrl": self.args.target, "maxDepth": 0,
                "idempotencyKey": "client-spider-live-" + uuid.uuid4().hex}
        response = self.mcp.call("zap_queue_client_spider_scan", args)
        first = field(response, "Job ID")
        self.job_ids.add(first)
        replay = self.mcp.call("zap_queue_client_spider_scan", args)
        self.check("queue idempotency returns same job", field(replay, "Job ID") == first
                   and "existing job returned" in replay, replay)
        self.rejected("queue idempotency rejects changed request", "zap_queue_client_spider_scan",
                      dict(args, maxDepth=1), "idempotency")
        running = self.wait(lambda: self.job_status(first),
                            lambda value: field(value, "Status").startswith(("RUNNING", "FAILED", "SUCCEEDED")))
        self.check("queued Client Spider dispatches and reports native ID",
                   field(running, "Status") == "RUNNING" and "ZAP Scan ID:" in running, running)
        native_id = field(running, "ZAP Scan ID")
        second_args = dict(args, idempotencyKey=args["idempotencyKey"] + "-pending")
        second = field(self.mcp.call("zap_queue_client_spider_scan", second_args), "Job ID")
        self.job_ids.add(second)
        pending = self.job_status(second)
        self.check("second job waits behind spider concurrency limit",
                   field(pending, "Status").startswith("QUEUED"), pending)
        self.cancel_job(second)
        self.check("pending queue job cancellation completes", True)
        self.check("cancelling pending job preserves running job",
                   field(self.job_status(first), "Status") == "RUNNING")
        self.cancel_job(first)
        stopped = self.wait(lambda: self.zap.view("clientSpider", "status", scanId=native_id),
                            lambda value: str(value.get("status")) == "100")
        self.check("running queue cancellation stops its native crawl", True, stopped)

    def guided_start(self):
        arguments = {"targetUrl": self.args.target, "strategy": "client",
                     "idempotencyKey": "client-live-" + uuid.uuid4().hex}
        if self.auth_session_id:
            arguments["authSessionId"] = self.auth_session_id
        response = self.mcp.call("zap_crawl_start", arguments)
        operation = field(response, "Operation ID")
        self.guided_ids.add(operation)
        self.check("guided crawl selects Client Spider", field(response, "Strategy") == "client", response)
        if self.auth_session_id:
            self.check("guided crawl binds prepared authentication",
                       field(response, "Authenticated Session") == self.auth_session_id)
        if self.args.expected_execution_mode:
            self.check("guided execution mode", field(response, "Execution Mode").lower()
                       == self.args.expected_execution_mode)
        return operation, response

    def guided_status(self, operation):
        return self.mcp.call("zap_crawl_status", {"operationId": operation})

    @staticmethod
    def guided_done(response):
        return bool(re.search(r"^Progress: 100%$|^Status: (SUCCEEDED|FAILED|CANCELLED)", response, re.M))

    def guided(self):
        operation, start = self.guided_start()
        self.guided_status(operation)
        self.mcp.call("zap_crawl_stop", {"operationId": operation})
        stopped = self.wait(lambda: self.guided_status(operation), self.guided_done)
        self.guided_ids.discard(operation)
        self.check("guided stop reaches terminal lifecycle", "FAILED" not in stopped, stopped)
        if field(start, "Execution Mode").lower() == "direct":
            self.check("guided stopped status disclaims coverage", "stopped scans as well" in stopped, stopped)

    def completion(self):
        previous = {str(message["id"]) for message in self.zap.messages(self.args.target)}
        started = time.monotonic()
        if self.args.mode == "expert":
            scan_id = self.start_direct(depth=2)
            result = self.wait(lambda: self.direct_status(scan_id), lambda value: progress(value) == 100)
            self.direct_ids.discard(scan_id)
        else:
            operation, _ = self.guided_start()
            result = self.wait(lambda: self.guided_status(operation), self.guided_done)
            self.guided_ids.discard(operation)
        elapsed = round(time.monotonic() - started, 2)
        self.check("crawl ends without test issuing stop", "FAILED" not in result and "CANCELLED" not in result,
                   {"elapsedSeconds": elapsed, "status": result,
                    "meaning": "Natural or configured duration termination; not proof of exhaustive coverage."})
        passive = self.mcp.call("zap_passive_scan_wait", {"timeoutSeconds": 60, "pollIntervalMs": 500})
        self.check("passive analysis drains", field(passive, "Completed") == "yes", passive)
        messages = [message for message in self.zap.messages(self.args.target)
                    if str(message["id"]) not in previous]
        api_messages = []
        authenticated_messages = []
        for message in messages:
            request = message.get("requestHeader", "").splitlines()
            response = message.get("responseHeader", "").splitlines()
            if request and re.search(r"/(?:rest|api)/", request[0]):
                api_messages.append({"id": message["id"], "request": request[0],
                                     "response": response[0] if response else "",
                                     "responseBodyPreview": "[omitted for authenticated crawl]" if self.auth_session_id
                                     else message.get("responseBody", "")[:300]})
            if self.auth_session_id and request:
                # Read credentials only to establish their presence; never retain values.
                header = message.get("requestHeader", "")
                authorization = bool(re.search(r"^Authorization:\s*\S+", header, re.M | re.I))
                cookies = re.findall(r"^Cookie:\s*(.+)$", header, re.M | re.I)
                token_cookie = any(re.search(
                    r"(?:^|;)\s*(?:[^=;\s]*(?:token|jwt|session)[^=;\s]*|sid|connect\.sid)=\s*[^;\s]+",
                    cookie, re.I) for cookie in cookies)
                request_parts = request[0].split()
                if len(request_parts) < 2:
                    continue
                request_url = urllib.parse.urlparse(urllib.parse.urljoin(self.args.target, request_parts[1]))
                verification_url = urllib.parse.urlparse(self.args.auth_verification_url)
                if (request_url.scheme, request_url.netloc, request_url.path) == (
                        verification_url.scheme, verification_url.netloc, verification_url.path):
                    authenticated_messages.append({
                        "id": message["id"], "request": request[0],
                        "response": response[0] if response else "",
                        "hasAuthorizationHeader": authorization,
                        "hasTokenCookie": token_cookie,
                        "containsExpectedUser": self.args.auth_expected_user in message.get("responseBody", "")})
        urls = self.zap.view("core", "urls", baseurl=self.args.target)["urls"]
        fresh_resources = requested_resources(messages, self.args.target)
        self.evidence["coverage"] = {"newMessageCount": len(messages), "apiMessages": api_messages,
                                     "urls": urls, "freshResourceUrls": sorted(fresh_resources),
                                     "attribution": "Fresh HTTP message IDs during this crawl in an isolated ZAP session."}
        if self.auth_session_id:
            self.evidence["coverage"]["authenticatedVerificationMessages"] = authenticated_messages
            verified = [item for item in authenticated_messages
                        if re.search(r"\s2\d\d(?:\s|$)", item["response"])
                        and item["containsExpectedUser"]
                        and (item["hasAuthorizationHeader"] or item["hasTokenCookie"])]
            self.check("crawl makes fresh authenticated verification requests for expected user", bool(verified),
                       {"verificationUrl": self.args.auth_verification_url,
                        "expectedUser": self.args.auth_expected_user,
                        "verificationMessages": authenticated_messages})
        successful = [item for item in api_messages if re.search(r"\s2\d\d(?:\s|$)", item["response"])]
        products = [item for item in successful if "/rest/products/search" in item["request"]]
        self.check("browser makes fresh successful Juice Shop API requests", bool(products),
                   {"successfulApiMessages": len(successful), "productSearchMessages": products})
        self.check("Client Spider discovers more than initial HTML", len(fresh_resources) >= 5,
                   {"newMessages": len(messages), "freshResources": len(fresh_resources)})

    def cleanup(self):
        errors = []
        for name, ids, parameter in [("zap_client_spider_stop", self.direct_ids, "scanId"),
                                     ("zap_scan_job_cancel", self.job_ids, "jobId"),
                                     ("zap_crawl_stop", self.guided_ids, "operationId")]:
            for identifier in list(ids):
                try:
                    self.mcp.call(name, {parameter: identifier})
                except Exception as error:
                    errors.append({"tool": name, "id": identifier, "error": str(error)})
        self.evidence["cleanupErrors"] = errors

    def run(self):
        try:
            self.setup()
            phases = self.args.phases.split(",")
            for phase in phases:
                print(f"RUN {phase}", flush=True)
                getattr(self, phase)()
            self.evidence["passed"] = True
            return 0
        except Exception as error:
            self.evidence["passed"] = False
            self.evidence["failure"] = str(error)
            print(f"FAIL {error}", file=sys.stderr, flush=True)
            return 1
        finally:
            self.cleanup()
            self.evidence["finishedAt"] = timestamp()
            self.save()
            print(f"Evidence: {self.args.evidence}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mcp-url", default=os.environ.get("MCP_URL", "http://localhost:7456/mcp"))
    parser.add_argument("--mcp-api-key", default=os.environ.get("MCP_API_KEY", ""))
    parser.add_argument("--zap-url", default=os.environ.get("ZAP_URL", "http://localhost:18080"))
    parser.add_argument("--zap-api-key", default=os.environ.get("ZAP_API_KEY", ""))
    parser.add_argument("--zap-host-header", default=os.environ.get("ZAP_HOST_HEADER", ""),
                        help="Host header recognized by ZAP when using a mapped Docker port, e.g. zap")
    parser.add_argument("--target", default="http://juice-shop:3000")
    parser.add_argument("--mode", choices=["expert", "guided"], default="expert")
    parser.add_argument("--expected-execution-mode", choices=["direct", "queue"])
    parser.add_argument("--auth-profile", help="Operator-configured browser authentication profile (guided only)")
    parser.add_argument("--auth-verification-url", help="Profile verification URL to require in fresh crawl traffic")
    parser.add_argument("--auth-expected-user", help="Expected authenticated identity, such as the Juice Shop user's email")
    parser.add_argument("--phases", help="Comma-separated validation,direct,queue,completion (expert) or validation,guided,completion (guided)")
    parser.add_argument("--timeout", type=int, default=180, help="Per lifecycle wait, in seconds")
    parser.add_argument("--poll-interval", type=float, default=3)
    parser.add_argument("--evidence", default="/tmp/client-spider-live-evidence.json")
    args = parser.parse_args()
    args.phases = args.phases or ("validation,direct,queue,completion" if args.mode == "expert"
                                 else "validation,guided,completion")
    valid = {"validation", "direct", "queue", "completion"} if args.mode == "expert" else {"validation", "guided", "completion"}
    if not set(args.phases.split(",")) <= valid:
        parser.error(f"Invalid phases for {args.mode}: {args.phases}")
    authentication = [args.auth_profile, args.auth_verification_url, args.auth_expected_user]
    if any(authentication) and (args.mode != "guided" or not all(authentication)):
        parser.error("Authentication requires guided mode and all of --auth-profile, --auth-verification-url, --auth-expected-user")
    if args.auth_verification_url:
        verification = urllib.parse.urlparse(args.auth_verification_url)
        target = urllib.parse.urlparse(args.target)
        if verification.scheme not in {"http", "https"} or (verification.scheme, verification.netloc) != (target.scheme, target.netloc):
            parser.error("Authentication verification URL must use the target's authorized origin")
    for name in ["mcp_url", "zap_url"]:
        url = urllib.parse.urlparse(getattr(args, name))
        if url.scheme not in {"http", "https"} or url.hostname not in {"localhost", "127.0.0.1", "::1"}:
            parser.error(f"{name} must point to the local isolated lab")
    if args.timeout <= 0 or args.poll_interval <= 0:
        parser.error("Timeout and poll interval must be positive")
    return Suite(args).run()


if __name__ == "__main__":
    sys.exit(main())
