#!/usr/bin/env python3
"""Optional, one-shot setup confirmation. Importing or previewing sends nothing."""

import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import urllib.error
import urllib.parse
import urllib.request


PLUGIN_ROOT = Path(__file__).resolve().parents[3]
DEFAULT_CONFIG = Path(__file__).resolve().parents[1] / "references/setup-reporting.json"
CLIENTS = {"codex", "cursor", "claude_code"}
VERSION = re.compile(
    r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?\Z"
)
STATUS = re.compile(
    r"\APassive scan status:[ \t]*\r?\nCompleted: (?:yes|no)[ \t]*\r?\n"
    r"Records remaining: \d+[ \t]*\r?\nActive tasks: (?:\d+|unknown)[ \t]*\r?\n"
    r"Scan only in scope: (?:true|false)(?:\s|\Z)"
)


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate field")
        result[key] = value
    return result


def valid_version(value):
    if not isinstance(value, str) or not 1 <= len(value) <= 32:
        return False
    match = VERSION.fullmatch(value)
    if match is None:
        return False
    prerelease = match.group(4)
    return not prerelease or all(
        not part.isdigit() or part == "0" or not part.startswith("0")
        for part in prerelease.split("."))


def read_json(path, limit, private=False):
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, "rb") as stream:
        metadata = os.fstat(stream.fileno())
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_size > limit:
            raise ValueError("unsupported file")
        if private and (metadata.st_uid != os.getuid() or metadata.st_mode & 0o077):
            raise ValueError("file must be private")
        data = stream.read(limit + 1)
        if len(data) > limit:
            raise ValueError("file too large")
    return json.loads(data, object_pairs_hook=unique_object)


def native_status_succeeded(reply):
    if not isinstance(reply, dict) or reply.get("isError") not in (None, False):
        return False
    if reply.get("error") is not None:
        return False
    structured = reply.get("structuredContent")
    if isinstance(structured, dict) and structured.get("ok") is False:
        return False
    content = reply.get("content")
    if not isinstance(content, list) or not content:
        return False
    texts = []
    for entry in content:
        if not isinstance(entry, dict):
            return False
        if entry.get("type") == "text":
            if not isinstance(entry.get("text"), str):
                return False
            texts.append(entry["text"])
    text = "\n".join(texts)
    try:
        decoded = json.loads(text)
    except ValueError:
        pass
    else:
        if not isinstance(decoded, str):
            return False
        text = decoded
    return STATUS.match(text) is not None


def validated_endpoint(value, allow_loopback=False):
    if not isinstance(value, str) or value != value.strip() or not value.isascii():
        raise ValueError("invalid destination")
    if any(ord(character) < 33 or ord(character) == 127 for character in value):
        raise ValueError("invalid destination")
    parsed = urllib.parse.urlsplit(value)
    if (parsed.username is not None or parsed.password is not None or parsed.query
            or parsed.fragment or parsed.path != "/v1/setup-completed" or not parsed.hostname):
        raise ValueError("invalid destination")
    host = parsed.hostname.lower()
    port = parsed.port
    if allow_loopback and parsed.scheme == "http" and host == "127.0.0.1" and port:
        return value
    if parsed.scheme != "https" or port not in (None, 443):
        raise ValueError("HTTPS is required")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if ("." not in host or host.endswith((".localhost", ".local", ".internal"))
                or not re.fullmatch(r"[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?", host)):
            raise ValueError("invalid public host")
    else:
        if not address.is_global:
            raise ValueError("public destination required")
    return value


def state_directory(value):
    return Path(value) if value is not None else Path.home() / ".local/state/mcp-zap-connect/setup-completions"


def receipt_path(installation, client, version, state_dir):
    installation = Path(installation).resolve(strict=True)
    if not installation.is_dir():
        raise ValueError("installation directory required")
    # This key stays on the local machine; neither it nor the path is sent.
    local_key = hashlib.sha256(os.fsencode(installation)).hexdigest()
    return state_directory(state_dir) / f"{local_key}-{client}-{version}.json"


def safe_directory(path, create=False):
    path = path.absolute()
    for component in (*reversed(path.parents), path):
        if component.is_symlink():
            raise ValueError("linked state directory")
    if create:
        path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.exists():
        metadata = path.stat()
        if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
            raise ValueError("state directory must be private")
    return path


def has_receipt(path, payload):
    if path.is_symlink():
        raise ValueError("linked receipt")
    if not path.exists():
        return False
    return read_json(path, 1024, private=True) == {"reported": True, **payload}


def _preview(client, installation, config_path, state_dir, allow_loopback, plugin_root):
    config = read_json(config_path, 1024)
    if (not isinstance(config, dict) or set(config) != {"schema_version", "setup_completion_url"}
            or type(config["schema_version"]) is not int or config["schema_version"] != 1):
        raise ValueError("invalid configuration")
    if config["setup_completion_url"] is None:
        return {"status": "disabled"}, None
    endpoint = validated_endpoint(config["setup_completion_url"], allow_loopback)
    manifest = read_json(Path(plugin_root) / "plugin.json", 32768)
    version = manifest.get("version")
    if client not in CLIENTS or not valid_version(version):
        raise ValueError("invalid event fields")
    payload = {"schema_version": 1, "event": "setup_completed", "client": client, "plugin_version": version}
    receipt = receipt_path(installation, client, version, state_dir)
    safe_directory(receipt.parent)
    if has_receipt(receipt, payload):
        return {"status": "already_reported"}, receipt
    encoded = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    approval = hashlib.sha256((endpoint + "\n" + encoded).encode("utf-8")).hexdigest()
    return {"status": "ready", "endpoint": endpoint, "payload": payload, "approval": approval}, receipt


def preview_setup(*, client, installation, config_path=DEFAULT_CONFIG, state_dir=None,
                  allow_loopback=False, plugin_root=PLUGIN_ROOT):
    try:
        return _preview(client, installation, config_path, state_dir, allow_loopback, plugin_root)[0]
    except (OSError, ValueError, TypeError, AttributeError):
        return {"status": "skipped", "reason": "invalid_configuration_or_state"}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file_pointer, code, message, headers, new_url):
        return None


def send_payload(endpoint, payload):
    data = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(endpoint, data=data, method="POST", headers={
        "Content-Type": "application/json", "Accept": "application/json",
        "User-Agent": "MCP-ZAP-Connect-Setup/1",
    })
    # Do not inherit proxy credentials, cookies or authentication from the AI client.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with opener.open(request, timeout=3) as response:
            return response.status
    except urllib.error.HTTPError as error:
        error.close()
        raise


def report_setup(*, client, installation, consent=False, native_client_verified=False,
                 status_file=None, approval=None, config_path=DEFAULT_CONFIG, state_dir=None,
                 allow_loopback=False, plugin_root=PLUGIN_ROOT, sender=None):
    if consent is not True:
        return {"status": "skipped", "reason": "consent_required"}
    if native_client_verified is not True:
        return {"status": "skipped", "reason": "native_client_verification_required"}
    try:
        preview, receipt = _preview(client, installation, config_path, state_dir, allow_loopback, plugin_root)
        if preview["status"] != "ready":
            return preview
        if not isinstance(approval, str) or approval != preview["approval"]:
            return {"status": "skipped", "reason": "approval_changed_or_missing"}
        if status_file is None or not native_status_succeeded(read_json(status_file, 16384, private=True)):
            return {"status": "skipped", "reason": "native_status_failed"}
        directory = safe_directory(receipt.parent, create=True)
        lock_path = directory / ".report.lock"
        descriptor = os.open(lock_path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        with os.fdopen(descriptor, "r+") as lock:
            metadata = os.fstat(lock.fileno())
            if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_mode & 0o077:
                raise ValueError("lock must be private")
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                return {"status": "skipped", "reason": "report_in_progress"}
            if has_receipt(receipt, preview["payload"]):
                return {"status": "already_reported"}
            try:
                status_code = (sender or send_payload)(preview["endpoint"], preview["payload"])
            except Exception:
                return {"status": "skipped", "reason": "delivery_failed"}
            if status_code != 202:
                return {"status": "skipped", "reason": "not_accepted"}
            temporary = None
            try:
                descriptor, temporary = tempfile.mkstemp(prefix=".receipt-", dir=directory)
                with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
                    json.dump({"reported": True, **preview["payload"]}, stream, sort_keys=True)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, receipt)
            except OSError:
                return {"status": "sent", "receipt_saved": False}
            finally:
                if temporary is not None:
                    Path(temporary).unlink(missing_ok=True)
            return {"status": "sent", "receipt_saved": True}
    except (OSError, ValueError, TypeError, AttributeError):
        return {"status": "skipped", "reason": "invalid_configuration_or_evidence"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("preview", "report"):
        command = commands.add_parser(name)
        command.add_argument("--client", choices=sorted(CLIENTS), required=True)
        command.add_argument("--installation", type=Path, required=True)
        command.add_argument("--config", dest="config_path", type=Path, default=DEFAULT_CONFIG)
        command.add_argument("--state-dir", type=Path)
        command.add_argument("--allow-loopback", action="store_true", help="Permit an explicit 127.0.0.1 HTTP test collector.")
        if name == "report":
            command.add_argument("--consent", choices=("yes", "no"), default="no")
            command.add_argument("--native-client-verified", action="store_true")
            command.add_argument("--status-file", type=Path)
            command.add_argument("--approval")
    arguments = vars(parser.parse_args(argv))
    command = arguments.pop("command")
    if command == "report":
        arguments["consent"] = arguments["consent"] == "yes"
        result = report_setup(**arguments)
    else:
        result = preview_setup(**arguments)
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
