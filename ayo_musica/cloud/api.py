"""Plain HTTP + JSON to the sync server, with the session token (urllib only)."""
import json
import os
import urllib.error
import urllib.request

AGENT = "AyoMusica-Desktop"


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


class Api:
    def __init__(self, account):
        self.account = account

    def _request(self, method, path, body=None, headers=None, timeout=30, token=None):
        request = urllib.request.Request(self.account.server + path, data=body, method=method)
        request.add_header("User-Agent", AGENT)
        session = token if token is not None else self.account.token
        if session:
            request.add_header("Authorization", f"Bearer {session}")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            try:
                message = json.loads(error.read() or b"{}").get("error")
            except ValueError:
                message = None
            if error.code == 401:
                self.account.sign_out()
            raise ApiError(error.code, message or f"O servidor respondeu {error.code}.") from None
        except (urllib.error.URLError, OSError) as error:
            raise ApiError(0, f"Sem conexão com o servidor ({getattr(error, 'reason', error)}).") from None

    def get(self, path, timeout=30, token=None):
        return json.loads(self._request("GET", path, timeout=timeout, token=token) or b"{}")

    def post(self, path, data):
        body = json.dumps(data).encode()
        return json.loads(self._request("POST", path, body, {"Content-Type": "application/json"}) or b"{}")

    def delete(self, path):
        return json.loads(self._request("DELETE", path) or b"{}")

    def upload(self, path, file_path, content_type, headers=None, method="POST", progress=None):
        """Sends a file without loading it all in memory."""
        size = os.path.getsize(file_path)
        request = urllib.request.Request(self.account.server + path, method=method)
        request.add_header("User-Agent", AGENT)
        request.add_header("Authorization", f"Bearer {self.account.token}")
        request.add_header("Content-Type", content_type)
        request.add_header("Content-Length", str(size))
        for key, value in (headers or {}).items():
            request.add_header(key, value)

        class Reader:
            def __init__(self, handle):
                self.handle, self.sent = handle, 0

            def read(self, amount=-1):
                chunk = self.handle.read(amount if amount and amount > 0 else 65536)
                self.sent += len(chunk)
                if progress:
                    progress(self.sent, size)
                return chunk

        with open(file_path, "rb") as handle:
            request.data = Reader(handle)
            try:
                with urllib.request.urlopen(request, timeout=600) as response:
                    return json.loads(response.read() or b"{}")
            except urllib.error.HTTPError as error:
                raise ApiError(error.code, f"O servidor respondeu {error.code}.") from None

    def download(self, path, target, progress=None):
        """Saves a server file; False when it does not exist."""
        request = urllib.request.Request(self.account.server + path)
        request.add_header("User-Agent", AGENT)
        request.add_header("Authorization", f"Bearer {self.account.token}")
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                total = int(response.headers.get("Content-Length") or 0)
                os.makedirs(os.path.dirname(target), exist_ok=True)
                temp = f"{target}.part"
                got = 0
                with open(temp, "wb") as out:
                    while True:
                        chunk = response.read(65536)
                        if not chunk:
                            break
                        out.write(chunk)
                        got += len(chunk)
                        if progress:
                            progress(got, total)
                os.replace(temp, target)
                return True
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return False
            if error.code == 401:
                self.account.sign_out()
            raise ApiError(error.code, f"O servidor respondeu {error.code}.") from None
