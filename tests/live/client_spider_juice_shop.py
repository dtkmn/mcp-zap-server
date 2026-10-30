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
                         "executionMode": "queue", "phases": args.phases.split(","),
                         "checks": [], "rpc": [], "coverage": {}}
        self.mcp = McpClient(args.mcp_url, args.mcp_api_key, record=self.evidence["rpc"].append)
        self.zap = ZapClient(args.zap_url, args.zap_api_key, args.zap_host_header)
        self.guided_ids, self.native_ids = {}, {}
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
            self.check(name, message.startswith(f"Tool {tool}:")
                       and expected.lower() in message.lower(), message)
            return
        if tool == "zap_crawl_start":
            self.remember_start(response)
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
        expected = ["zap_crawl_start", "zap_crawl_status", "zap_crawl_stop",
                    "zap_passive_scan_status", "zap_passive_scan_wait"]
        if self.args.auth_profile:
            expected += ["zap_auth_session_prepare", "zap_auth_session_validate"]
        self.check("required tools published", all(name in self.tools for name in expected), expected)
        retired = {"zap_client_spider_start", "zap_client_spider_status", "zap_client_spider_stop",
                   "zap_queue_client_spider_scan", "zap_scan_job_status", "zap_scan_job_cancel"}
        self.check("guided catalog omits raw Client Spider and job tools", not retired.intersection(self.tools))
        schema = self.tools["zap_crawl_start"]["inputSchema"]
        properties = schema.get("properties", {})
        self.check("guided crawl schema", set(schema.get("required", [])) == {"targetUrl"}
                   and {"targetUrl", "strategy", "idempotencyKey", "authSessionId"} <= properties.keys()
                   and not {"maxDepth", "contextName", "userName"}.intersection(properties), schema)
        for tool in ["zap_crawl_status", "zap_crawl_stop"]:
            self.check(f"{tool} requires operationId",
                       self.tools[tool]["inputSchema"].get("required") == ["operationId"])
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
        for name in ["zap_crawl_status", "zap_crawl_stop"]:
            for invalid in [" ", "2147483647"]:
                self.rejected(f"{name} rejects invalid operation {invalid!r}", name,
                              {"operationId": invalid}, "operationId")
        self.rejected("guided start rejects blank target", "zap_crawl_start",
                      {"targetUrl": " ", "strategy": "client"}, "targetUrl")
        self.rejected("guided start rejects unsupported strategy", "zap_crawl_start",
                      {"targetUrl": self.args.target, "strategy": "not-a-crawler"}, "strategy")
        self.rejected("guided start rejects unknown auth session", "zap_crawl_start",
                      {"targetUrl": self.args.target, "strategy": "client",
                       "authSessionId": "unknown-client-live-" + uuid.uuid4().hex}, "Unknown auth session ID")

    def start_arguments(self):
        arguments = {"targetUrl": self.args.target, "strategy": "client",
                     "idempotencyKey": "client-live-" + uuid.uuid4().hex}
        if self.auth_session_id:
            arguments["authSessionId"] = self.auth_session_id
        return arguments

    def remember_start(self, response):
        try:
            operation = field(response, "Operation ID")
        except AssertionError:
            return None
        try:
            job = field(response, "Job ID")
        except AssertionError:
            job = None
        self.guided_ids[operation] = job
        return operation

    def guided_start(self, arguments=None):
        arguments = arguments if arguments is not None else self.start_arguments()
        try:
            response = self.mcp.call("zap_crawl_start", arguments)
        except Exception as error:
            self.evidence.setdefault("unconfirmedStartRequests", []).append({"error": str(error)})
            raise
        operation = self.remember_start(response)
        if not operation:
            self.evidence.setdefault("unconfirmedStartRequests", []).append({"reason": "No Operation ID returned"})
            raise AssertionError(f"Start returned no usable guided operation: {response}")
        job = field(response, "Job ID")
        self.check("operation and job IDs remain distinct", operation != job)
        self.check("guided crawl selects Client Spider", field(response, "Strategy") == "client", response)
        self.check("guided execution mode is queue", field(response, "Execution Mode").lower() == "queue", response)
        if self.auth_session_id:
            self.check("guided crawl binds prepared authentication",
                       field(response, "Authenticated Session") == self.auth_session_id)
        if self.queue_state(response) in {"START_OUTCOME_UNKNOWN", "FAILED", "CANCELLED", "SUCCEEDED"}:
            raise AssertionError(f"Start did not leave an active queued operation: {response}")
        return operation, response

    def guided_status(self, operation):
        response = self.mcp.call("zap_crawl_status", {"operationId": operation})
        job = field(response, "Job ID")
        if self.guided_ids.get(operation) not in {None, job}:
            raise AssertionError(f"Operation changed its job ID: {response}")
        if operation in self.guided_ids:
            self.guided_ids[operation] = job
        native = self.optional_field(response, "ZAP Scan ID")
        if native:
            if self.native_ids.get(job) not in {None, native}:
                raise AssertionError(f"Job changed its native ID: {response}")
            self.native_ids[job] = native
        return response

    @staticmethod
    def optional_field(response, label):
        try:
            return field(response, label)
        except AssertionError:
            return None

    @staticmethod
    def queue_state(response):
        state = field(response, "Status").split()[0]
        if state not in {"QUEUED", "STARTING", "START_OUTCOME_UNKNOWN", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"}:
            raise AssertionError(f"Unrecognized queue state: {response}")
        return state

    def wait_for_state(self, operation, expected):
        def reached(response):
            state = self.queue_state(response)
            if state in {"START_OUTCOME_UNKNOWN", "FAILED"} or (
                    state in {"SUCCEEDED", "CANCELLED"} and state != expected):
                raise AssertionError(f"Expected {expected}, received {response}")
            if expected == "RUNNING" and "cancellation" in field(response, "Status").lower():
                raise AssertionError(f"Running operation has unresolved cancellation: {response}")
            return state == expected
        return self.wait(lambda: self.guided_status(operation), reached)

    def forget_job(self, job):
        self.guided_ids = {operation: owner for operation, owner in self.guided_ids.items() if owner != job}

    def stop_guided(self, operation, native_id=None):
        self.mcp.call("zap_crawl_stop", {"operationId": operation})
        stopped = self.wait_for_state(operation, "CANCELLED")
        self.check("cancelled crawl does not establish successful coverage",
                   "before trusting this scan as evidence" in stopped, stopped)
        reported_native = self.optional_field(stopped, "ZAP Scan ID")
        if native_id:
            self.check("cancelled job retains its native ID", reported_native == native_id, stopped)
        if reported_native:
            component = {"client": "clientSpider", "http": "spider"}.get(field(stopped, "Strategy"))
            if not component:
                raise AssertionError(f"Cannot independently verify this native crawler: {stopped}")
            native = self.wait(lambda: self.zap.view(component, "status", scanId=reported_native),
                               lambda value: str(value.get("status")) == "100")
            self.check("guided cancellation stops its native crawl", True, native)
        self.forget_job(field(stopped, "Job ID"))
        return stopped

    def assert_running(self, operation, native_id):
        status = self.guided_status(operation)
        self.check("other crawl remains independently running",
                   self.queue_state(status) == "RUNNING"
                   and "cancellation" not in field(status, "Status").lower(), status)
        native = self.zap.view("clientSpider", "status", scanId=native_id)
        self.check("other native crawl remains in progress", int(native["status"]) < 100, native)

    def lifecycle(self):
        arguments = self.start_arguments()
        first, _ = self.guided_start(arguments)
        first_running = self.wait_for_state(first, "RUNNING")
        first_job, first_native = field(first_running, "Job ID"), field(first_running, "ZAP Scan ID")
        replay, replay_response = self.guided_start(arguments)
        self.check("idempotency returns the same job", field(replay_response, "Job ID") == first_job
                   and "existing job returned" in replay_response, replay_response)
        self.check("both replay handles resolve the same native crawl",
                   field(self.guided_status(replay), "ZAP Scan ID") == first_native)
        changed = dict(arguments, strategy="http")
        changed.pop("authSessionId", None)
        self.rejected("idempotency rejects a changed request", "zap_crawl_start", changed, "idempotency")

        second, _ = self.guided_start()
        second_running = self.wait_for_state(second, "RUNNING")
        second_job, second_native = field(second_running, "Job ID"), field(second_running, "ZAP Scan ID")
        self.check("two guided crawls have distinct jobs and native IDs",
                   first_job != second_job and first_native != second_native,
                   {"jobs": [first_job, second_job], "nativeIds": [first_native, second_native]})
        self.assert_running(first, first_native)
        self.assert_running(second, second_native)

        pending, _ = self.guided_start()
        queued = self.guided_status(pending)
        self.check("third job waits behind spider concurrency limit two",
                   self.queue_state(queued) == "QUEUED"
                   and not self.optional_field(queued, "ZAP Scan ID")
                   and not self.optional_field(queued, "Claim Owner"), queued)
        cancelled = self.stop_guided(pending)
        self.check("pending cancellation never starts a native crawl",
                   not self.optional_field(cancelled, "ZAP Scan ID"), cancelled)
        self.assert_running(first, first_native)
        self.assert_running(second, second_native)
        self.stop_guided(first, first_native)
        self.assert_running(second, second_native)
        self.stop_guided(second, second_native)

    def completion(self):
        previous = {str(message["id"]) for message in self.zap.messages(self.args.target)}
        started = time.monotonic()
        operation, _ = self.guided_start()
        result = self.wait_for_state(operation, "SUCCEEDED")
        elapsed = round(time.monotonic() - started, 2)
        self.check("crawl ends without test issuing stop", self.queue_state(result) == "SUCCEEDED",
                   {"elapsedSeconds": elapsed, "status": result,
                    "meaning": "Natural or configured duration termination; not proof of exhaustive coverage."})
        self.check("Client Spider termination disclaims exhaustive coverage",
                   "successful completion is unconfirmed" in result, result)
        self.forget_job(field(result, "Job ID"))
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
        attempted = set()
        for operation, job in list(self.guided_ids.items()):
            owner = job or operation
            if owner in attempted:
                continue
            attempted.add(owner)
            try:
                self.stop_guided(operation, self.native_ids.get(job))
            except Exception as error:
                errors.append({"tool": "zap_crawl_stop", "operationId": operation, "jobId": job,
                               "error": str(error)})
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


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mcp-url", default=os.environ.get("MCP_URL", "http://localhost:7456/mcp"))
    parser.add_argument("--mcp-api-key", default=os.environ.get("MCP_API_KEY", ""))
    parser.add_argument("--zap-url", default=os.environ.get("ZAP_URL", "http://localhost:18080"))
    parser.add_argument("--zap-api-key", default=os.environ.get("ZAP_API_KEY", ""))
    parser.add_argument("--zap-host-header", default=os.environ.get("ZAP_HOST_HEADER", ""),
                        help="Host header recognized by ZAP when using a mapped Docker port, e.g. zap")
    parser.add_argument("--target", default="http://juice-shop:3000")
    parser.add_argument("--auth-profile", help="Operator-configured browser authentication profile")
    parser.add_argument("--auth-verification-url", help="Profile verification URL to require in fresh crawl traffic")
    parser.add_argument("--auth-expected-user", help="Expected authenticated identity, such as the Juice Shop user's email")
    parser.add_argument("--phases", default="validation,lifecycle,completion",
                        help="Comma-separated subset of validation,lifecycle,completion; omitted phases are not checked")
    parser.add_argument("--timeout", type=int, default=240, help="Per lifecycle wait, in seconds")
    parser.add_argument("--poll-interval", type=float, default=3)
    parser.add_argument("--evidence", default="/tmp/client-spider-live-evidence.json")
    args = parser.parse_args(argv)
    if not set(args.phases.split(",")) <= {"validation", "lifecycle", "completion"}:
        parser.error(f"Invalid guided phases: {args.phases}")
    authentication = [args.auth_profile, args.auth_verification_url, args.auth_expected_user]
    if any(authentication) and not all(authentication):
        parser.error("Authentication requires all of --auth-profile, --auth-verification-url, --auth-expected-user")
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
