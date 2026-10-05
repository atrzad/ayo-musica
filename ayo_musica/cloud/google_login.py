"""Sign in with Google from the desktop: the installed-app flow (browser + a one-time local address + PKCE).
Google's answer is a code; it is exchanged for an ID token, which our server checks and turns into its session."""
import base64
import hashlib
import http.server
import json
import secrets
import threading
import urllib.parse
import urllib.request

AUTH = "https://accounts.google.com/o/oauth2/v2/auth"
TOKEN = "https://oauth2.googleapis.com/token"
PAGE = ("<html><head><meta charset='utf-8'><title>Ayo Música</title></head><body style='font-family:sans-serif;"
        "text-align:center;padding:4em'><h2>{}</h2><p>Pode fechar esta aba e voltar ao Ayo Música.</p></body></html>")


def sign_in(client_id, client_secret, open_browser, timeout=300):
    """Blocks until Google answers (call in the background). Returns the ID token."""
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    result = {}
    done = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):  # noqa: N802 (http.server's name)
            query = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if query.get("state", [""])[0] != state:
                self.send_response(400)
                self.end_headers()
                return
            result.update({key: values[0] for key, values in query.items()})
            ok = "code" in result
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write(PAGE.format("Pronto, você entrou." if ok else "O login foi cancelado.").encode())
            done.set()

        def log_message(self, *_args):
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://127.0.0.1:{server.server_port}"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        open_browser(AUTH + "?" + urllib.parse.urlencode({
            "client_id": client_id, "redirect_uri": redirect, "response_type": "code", "scope": "openid email profile",
            "code_challenge": challenge, "code_challenge_method": "S256", "state": state, "prompt": "select_account",
        }))
        if not done.wait(timeout):
            raise RuntimeError("O login demorou demais. Tente de novo.")
    finally:
        server.shutdown()
    if "code" not in result:
        raise RuntimeError("O login com o Google foi cancelado.")
    data = urllib.parse.urlencode({"code": result["code"], "client_id": client_id, "client_secret": client_secret,
                                   "redirect_uri": redirect, "grant_type": "authorization_code",
                                   "code_verifier": verifier}).encode()
    with urllib.request.urlopen(urllib.request.Request(TOKEN, data=data), timeout=30) as response:
        tokens = json.loads(response.read())
    if "id_token" not in tokens:
        raise RuntimeError("O Google não devolveu a conta.")
    return tokens["id_token"]
