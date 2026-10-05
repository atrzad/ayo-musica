"""Who is signed in, on which server, and this computer's device id. Read from the settings once and kept in
memory: the player and the background work use it from other threads, and the settings database belongs to the
main loop (writes go there)."""
import socket
import threading
import uuid

from gi.repository import GLib

DEFAULT_SERVER = "https://ayo-musica.tail9ff58.ts.net"
FIELDS = ("server", "token", "email", "name")


class Account:
    def __init__(self, store):
        self.store = store
        self.listeners = []
        self.lock = threading.Lock()
        self.device = store.setting("music.cloud_device") or str(uuid.uuid4())
        store.set_setting("music.cloud_device", self.device)
        self.device_name = f"PC {socket.gethostname()}"
        self.values = {field: store.setting(f"music.cloud_{field}") or "" for field in FIELDS}

    @property
    def server(self):
        return (self.values["server"] or DEFAULT_SERVER).rstrip("/")

    @property
    def token(self):
        return self.values["token"]

    @property
    def email(self):
        return self.values["email"]

    @property
    def name(self):
        return self.values["name"]

    @property
    def signed_in(self):
        return bool(self.values["token"])

    def owns(self, url):
        return self.signed_in and str(url).startswith(self.server)

    def set_server(self, url):
        self._set(server=(url or "").strip().rstrip("/"))

    def sign_in(self, token, email, name):
        self._set(token=token, email=email, name=name)
        self._changed()

    def sign_out(self):
        if not self.values["token"]:
            return
        self._set(token="", email="", name="")
        self._changed()

    def _set(self, **changes):
        with self.lock:
            self.values.update(changes)

        def save():
            for field, value in changes.items():
                self.store.set_setting(f"music.cloud_{field}", value)
            return False
        if threading.current_thread() is threading.main_thread():
            save()
        else:
            GLib.idle_add(save)

    def _changed(self):
        for callback in list(self.listeners):
            callback()
