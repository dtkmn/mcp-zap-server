"""Owned HTTP scan fixture for the isolated EC2 example; never publish its port."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit


class SmokeApi(BaseHTTPRequestHandler):
    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/":
            status, content_type = 200, "text/html"
            payload = b'<html><body><a href="/products">Products</a></body></html>'
        elif path == "/products":
            status, content_type = 200, "application/json"
            payload = json.dumps({"products": [{"id": 1, "name": "Example item"}]}).encode()
        elif path == "/imported-only":
            status, content_type = 200, "application/json"
            payload = json.dumps({"imported_only": True}).encode()
        elif path == "/openapi.json":
            status, content_type = 200, "application/json"
            payload = json.dumps({
                "openapi": "3.0.3",
                "info": {"title": "EC2 smoke-test API", "version": "1.0.0"},
                "servers": [{"url": "http://smoke-target:8080"}],
                "paths": {
                    "/imported-only": {
                        "get": {
                            "operationId": "getImportedOnly",
                            "responses": {"200": {"description": "Import-only endpoint"}},
                        },
                    },
                },
            }).encode()
        else:
            status, content_type, payload = 404, "text/plain", b"not found"

        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 8080), SmokeApi).serve_forever()
