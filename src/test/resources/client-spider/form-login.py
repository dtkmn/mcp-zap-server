import base64
import json
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from secrets import token_urlsafe
from urllib.parse import parse_qs, urlsplit


class FormLoginFixture(BaseHTTPRequestHandler):
    sessions = set()
    bearer_sessions = set()

    def respond(self, status, body, cookie=None, location=None, content_type="text/html; charset=utf-8"):
        data = body.encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        if cookie:
            self.send_header("Set-Cookie", cookie)
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(data)

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()
        if urlsplit(self.path).path == "/bearer/login":
            try:
                credentials = json.loads(body)
            except json.JSONDecodeError:
                self.respond(403, "Login required")
                return
            if credentials.get("username") != "scan-user" or credentials.get("password") != "fixture-password":
                self.respond(403, "Login required")
                return
            # A JWT-shaped synthetic token, accepted only when issued by this fixture.
            payload = base64.urlsafe_b64encode(json.dumps({"sub": "scan-user", "nonce": token_urlsafe(24)}).encode())
            token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9." + payload.decode().rstrip("=") + "." + token_urlsafe(24)
            self.bearer_sessions.add(token)
            self.respond(200, json.dumps({"access_token": token, "token_type": "Bearer"}), content_type="application/json")
            return
        form = parse_qs(body)
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
        if path == "/bearer/login":
            self.respond(200, """<!doctype html><html><body>
                <form id="login" method="post" action="/bearer/login">
                <label for="username">Username</label><input id="username" name="username" autocomplete="username">
                <label for="password">Password</label><input id="password" name="password" type="password"
                autocomplete="current-password"><button type="submit">Login</button></form>
                <script>
                document.getElementById('login').addEventListener('submit', async event => {
                    event.preventDefault();
                    const response = await fetch('/bearer/login', {method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify({username: document.getElementById('username').value,
                            password: document.getElementById('password').value})});
                    if (response.ok) {
                        const data = await response.json();
                        localStorage.setItem('access_token', data.access_token);
                        location.href = '/bearer/app';
                    }
                });
                </script></body></html>""")
            return
        if path == "/bearer/app":
            self.respond(200, """<!doctype html><html><body><div id="identity"></div>
                <script>
                const headers = {Authorization: 'Bearer ' + localStorage.getItem('access_token')};
                fetch('/bearer/user', {headers}).then(async response => {
                    document.getElementById('identity').textContent = await response.text();
                    if (response.ok && location.search) {
                        fetch('/bearer/' + ['client', 'discovered'].join('-') + location.search, {headers});
                    }
                });
                </script></body></html>""")
            return
        if path in ("/bearer/user", "/bearer/client-discovered"):
            authorization = self.headers.get("Authorization", "")
            if not authorization.startswith("Bearer ") or authorization[7:] not in self.bearer_sessions:
                self.respond(403, "Login required")
            elif path == "/bearer/user":
                self.respond(200, "Signed in as scan-user")
            else:
                self.respond(200, "Signed in as scan-user: authenticated JavaScript resource")
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
