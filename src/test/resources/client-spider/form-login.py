from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from secrets import token_urlsafe
from urllib.parse import parse_qs, urlsplit


class FormLoginFixture(BaseHTTPRequestHandler):
    sessions = set()

    def respond(self, status, body, cookie=None, location=None):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        form = parse_qs(self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode())
        if (urlsplit(self.path).path != "/login"
                or form.get("username") != ["scan-user"]
                or form.get("password") != ["fixture-password"]):
            self.respond(403, "Login required")
            return
        session = token_urlsafe(24)
        self.sessions.add(session)
        self.respond(303, "Signed in as scan-user", f"session={session}; Path=/; HttpOnly", "/authenticated")

    def do_GET(self):
        path = urlsplit(self.path).path
        if path == "/":
            self.respond(200, "Form-login crawl fixture")
            return
        if path == "/login":
            self.respond(200, '<form method="post" action="/login"><label for="username">Username</label>'
                         '<input id="username" name="username" autocomplete="username">'
                         '<label for="password">Password</label><input id="password" name="password" '
                         'type="password" autocomplete="current-password"><button type="submit">Login</button></form>')
            return
        if path == "/unexpected-response":
            self.respond(500, "<!doctype html><html><body>Temporarily unavailable</body></html>")
            return
        cookie = SimpleCookie(self.headers.get("Cookie", ""))
        session = cookie.get("session")
        if session is None or session.value not in self.sessions:
            self.respond(403, "Login required")
        elif path == "/authenticated":
            self.respond(200, "Signed in as scan-user")
        elif path == "/protected":
            self.respond(200, """<!doctype html><html><body>Signed in as scan-user
                <script>fetch('/protected/' + ['client', 'discovered'].join('-') + location.search);</script>
                </body></html>""")
        elif path == "/protected/client-discovered":
            self.respond(200, "Signed in as scan-user: authenticated JavaScript resource")
        else:
            self.respond(404, "Not found")


ThreadingHTTPServer(("0.0.0.0", 8080), FormLoginFixture).serve_forever()
