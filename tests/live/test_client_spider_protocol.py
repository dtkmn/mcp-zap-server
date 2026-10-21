"""Offline fixtures for the live harness's JSON/SSE response reader."""

import io
import unittest

from client_spider_juice_shop import content_text, field, requested_resources, rpc_response


class Response(io.BytesIO):
    def __init__(self, body, content_type):
        super().__init__(body.encode())
        self.headers = {"Content-Type": content_type}


class ProtocolTests(unittest.TestCase):
    def test_json_result(self):
        response = Response('{"jsonrpc":"2.0","id":4,"result":{"tools":[]}}', "application/json")
        self.assertEqual({"tools": []}, rpc_response(response, 4)["result"])

    def test_sse_skips_notifications_and_unrelated_responses(self):
        response = Response(
            ': keepalive\r\n\r\n'
            'event: message\r\ndata: {"method":"notifications/progress"}\r\n\r\n'
            'data: {"id":3,"result":{}}\r\n\r\n'
            'event: message\r\ndata: {"jsonrpc":"2.0",\r\n'
            'data: "id":4,"result":{"ok":true}}\r\n\r\n'
            'data: never-read\r\n\r\n', "text/event-stream; charset=utf-8")
        self.assertEqual({"ok": True}, rpc_response(response, 4)["result"])

    def test_empty_json_notification_response(self):
        self.assertIsNone(rpc_response(Response('', "application/json"), None))

    def test_unfinished_sse_fails_instead_of_reporting_success(self):
        with self.assertRaisesRegex(RuntimeError, "before the matching"):
            rpc_response(Response('data: {"id":2}\n\n', "text/event-stream"), 4)

    def test_plain_and_json_encoded_tool_text(self):
        for value in ['Scan ID: 2\nProgress: 100%', '"Scan ID: 2\\nProgress: 100%"']:
            result = {"content": [{"type": "text", "text": value}]}
            self.assertEqual("2", field(content_text(result), "Scan ID"))

    def test_native_scan_id_is_not_confused_with_job_id(self):
        response = 'Job ID: abc\nZAP Scan ID: 2\nProgress: 0%'
        self.assertEqual("2", field(response, "ZAP Scan ID"))
        with self.assertRaises(AssertionError):
            field(response, "Scan ID")

    def test_blank_field_cannot_consume_next_line(self):
        for response in ["Scan ID: \nProgress: 100%", "Scan ID:\r\nProgress: 100%"]:
            with self.assertRaises(AssertionError):
                field(response, "Scan ID")
        self.assertEqual("2", field("Scan ID: 2\r\nProgress: 100%", "Scan ID"))

    def test_repeated_requests_and_query_variations_do_not_inflate_resource_coverage(self):
        messages = [{"requestHeader": f"GET /rest/products/search?q={index} HTTP/1.1\r\n"}
                    for index in range(5)]
        self.assertEqual({"http://juice-shop:3000/rest/products/search"},
                         requested_resources(messages, "http://juice-shop:3000"))

    def test_resource_coverage_uses_only_supplied_fresh_requests(self):
        messages = [
            {"requestHeader": "GET http://juice-shop:3000/ HTTP/1.1\r\n"},
            {"requestHeader": "GET /main.js HTTP/1.1\r\n"},
            {"requestHeader": "GET /rest/products/search HTTP/1.1\r\n"},
            {"requestHeader": ""},
        ]
        self.assertEqual({"http://juice-shop:3000/", "http://juice-shop:3000/main.js",
                          "http://juice-shop:3000/rest/products/search"},
                         requested_resources(messages, "http://juice-shop:3000"))


if __name__ == "__main__":
    unittest.main()
