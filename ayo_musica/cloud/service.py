"""Everything the account does on the desktop, for the window: sign in, sync, the cloud library, this computer as a
device (its state for "continuar" and the remote control) and the other devices."""
import os
import threading
import time

from gi.repository import Gio, GLib

from .. import paths
from ..tasks import background
from . import google_login, keys as songkeys
from .account import Account
from .api import Api
from .library_sync import DesktopSync
from .sync import SyncEngine
from .tracks import CloudLibrary

SOURCES = (("all", "Tudo"), ("local", "Neste computador"), ("cloud", "Nuvem"))


def on_main(work):
    """Runs `work` on the GTK main loop and waits for its result (the music database belongs to that thread)."""
    if threading.current_thread() is threading.main_thread():
        return work()
    box, done = {}, threading.Event()

    def run():
        try:
            box["value"] = work()
        except Exception as error:  # noqa: BLE001 (re-raised in the calling thread)
            box["error"] = error
        done.set()
        return False
    GLib.idle_add(run)
    done.wait()
    if "error" in box:
        raise box["error"]
    return box.get("value")


class MainLoopHooks:
    """The sync hooks, with every database touch done on the main loop (the network stays in the background)."""

    def __init__(self, inner):
        self.inner = inner

    def songs(self):
        return on_main(self.inner.songs)

    def export(self, matcher):
        return on_main(lambda: self.inner.export(matcher))

    def apply(self, kind, key, value, matcher):
        return on_main(lambda: self.inner.apply(kind, key, value, matcher))

    def manages(self, kind, key):
        return self.inner.manages(kind, key)


class CloudService:
    def __init__(self, page):
        self.page = page
        self.account = Account(page.store)
        self.api = Api(self.account)
        folder = os.path.join(paths.data_dir(), "cloud")
        self.library = CloudLibrary(self.api, folder)
        self.sync_hooks = DesktopSync(page.music, page.store, self._songs, lambda: self.account.device, self._set_theme)
        self.engine = SyncEngine(os.path.join(folder, "sync-state.json"), MainLoopHooks(self.sync_hooks), lambda: self.account.device,
                                 lambda since, changes: self.api.post("/api/sync", {"device": self.account.device,
                                                                                    "since": since, "changes": changes}))
        self.sync_hooks.engine = self.engine
        self.source = page.store.setting("music.cloud_source", "all")
        self.status = {"running": False, "last": 0, "message": "", "error": None}
        self.devices = []
        self.listeners = []  # called on the main loop when status, library or devices change
        self._syncing = False
        self._publish_timer = None
        self._stop = threading.Event()
        self.account.listeners.append(self._account_changed)
        threading.Thread(target=self._listen, name="ayo-remoto", daemon=True).start()
        GLib.timeout_add_seconds(300, self._periodic)
        GLib.timeout_add_seconds(20, self._publish_while_playing)
        if self.account.signed_in:  # already signed in: sync and look at the other devices shortly after opening
            GLib.timeout_add_seconds(3, lambda: (self.sync_now(), self.refresh_devices()) and False)

    # ── library rows ───────────────────────────────────────────────────────
    def _songs(self):
        """Every track this computer knows with its key: its own files and the cloud's."""
        local = [(row, songkeys.song_key(row.get("artist"), row.get("title"), row.get("duration")), False)
                 for row in self.page.music.library()]
        cloud = [(self.library.row(track), track.get("songKey") or "", True) for track in self.library.tracks]
        return local + cloud

    def rows(self, local_rows):
        """The library as shown: everything (a song in both places once, this computer's), this computer's or the cloud's."""
        if not self.account.signed_in or self.source == "local":
            return local_rows
        cloud = [self.library.row(track) for track in self.library.tracks]
        if self.source == "cloud":
            return cloud
        here = {songkeys.name(songkeys.song_key(r.get("artist"), r.get("title"), r.get("duration"))) for r in local_rows}
        return local_rows + [r for r in cloud if songkeys.name(songkeys.song_key(r["artist"], r["title"], r["duration"])) not in here]

    def set_source(self, source):
        if source == self.source:
            return
        self.source = source
        self.page.store.set_setting("music.cloud_source", source)
        self.page.reload_library()
        self._notify()

    def is_cloud(self, path):
        return self.account.owns(path) or str(path).startswith(self.library.downloads)

    def track_of(self, path):
        for track in self.library.tracks:
            if path in (self.library.audio_url(track["id"]), self.library.downloaded(track["id"])):
                return track
        return None

    def http_headers(self, uri):
        """For the player: cloud songs stream with the session."""
        return {"Authorization": f"Bearer {self.account.token}"} if self.account.owns(uri) else {}

    # ── account ────────────────────────────────────────────────────────────
    def sign_in_google(self, done):
        def work():
            config = self.api.get("/api/config")
            client, secret = config.get("desktopClientId"), config.get("desktopClientSecret")
            if not client:
                raise RuntimeError("O servidor ainda não tem o login do Google configurado (GOOGLE_DESKTOP_CLIENT_ID).")
            opened = threading.Event()

            def open_browser(url):
                GLib.idle_add(lambda: Gio.AppInfo.launch_default_for_uri(url, None) and False)
                opened.set()
            id_token = google_login.sign_in(client, secret, open_browser)
            return self._login(id_token)
        background(work, lambda result, error: done(error))

    def sign_in_session(self, token, done):
        def work():
            me = self.api.get("/api/me", token=token.strip())
            user = me.get("user") or {}
            self.account.sign_in(token.strip(), user.get("email", ""), user.get("name", ""))
        background(work, lambda result, error: done(error))

    def _login(self, id_token):
        answer = self.api.post("/api/login", {"idToken": id_token, "device": self.account.device_name})
        user = answer.get("user") or {}
        self.account.sign_in(answer["token"], user.get("email", ""), user.get("name", ""))

    def sign_out(self):
        token = self.account.token

        def work():
            if token:
                try:
                    self.api.post("/api/logout", {})
                except Exception:  # noqa: BLE001
                    pass
        background(work, lambda *_: None)
        self.account.sign_out()

    def _account_changed(self):
        def update():
            if not self.account.signed_in:
                self.library.clear()
                self.devices = []
            self.page.reload_library()
            self._notify()
            if self.account.signed_in:
                self.sync_now()
            return False
        GLib.idle_add(update)

    # ── sync ───────────────────────────────────────────────────────────────
    def sync_now(self, quiet=False):
        if not self.account.signed_in or self._syncing:
            return
        self._syncing = True
        self.status.update(running=True, error=None, message=self.status["message"] if quiet else "Sincronizando…")
        self._notify()
        email, server = self.account.email, self.account.server

        def work():
            if not self.engine.belongs_to(server, email):
                self.engine.reset(server, email)
            self.library.refresh()
            return self.engine.sync()

        def done(result, error):
            self._syncing = False
            self.status["running"] = False
            if error:
                self.status["error"] = error
            else:
                self.status.update(last=time.time(), message="Sincronizado", error=None)
            self.page.reload_library()
            self.page.refresh_playlists()
            self._notify()
            if not error:  # then the album covers, quietly
                background(self.library.fetch_covers, lambda fetched, _e: fetched and self.page.reload_library())
        background(work, done)

    def _periodic(self):
        self.sync_now(quiet=True)
        return True

    def local_not_in_cloud(self):
        there = {songkeys.name(t.get("songKey") or "") for t in self.library.tracks}
        return [row for row in self.page.music.library()
                if songkeys.name(songkeys.song_key(row.get("artist"), row.get("title"), row.get("duration"))) not in there]

    def upload(self, paths_):
        paths_ = [p for p in paths_ if not self.is_cloud(p)]

        def work():
            sent = 0
            for number, path in enumerate(paths_, start=1):
                self.status["message"] = f"Enviando {number} de {len(paths_)} para a nuvem…"
                GLib.idle_add(lambda: self._notify() and False)
                if self.library.upload(path):
                    sent += 1
            self.library.refresh()
            return sent

        def done(sent, error):
            self.status.update(message=f"{sent or 0} enviadas para a nuvem", error=error)
            self.page.reload_library()
            self._notify()
        background(work, done)

    def download(self, paths_):
        tracks = [t for t in (self.track_of(p) for p in paths_) if t and not self.library.downloaded(t["id"])]

        def work():
            for track in tracks:
                self.library.download(track)
            return len(tracks)

        def done(count, error):
            self.status.update(message=f"{count or 0} baixadas da nuvem", error=error)
            self.page.reload_library()
            self._notify()
        background(work, done)

    def remove_download(self, path):
        track = self.track_of(path)
        if track:
            self.library.remove_download(track["id"])
            self.page.reload_library()

    def delete(self, path):
        track = self.track_of(path)
        if track:
            background(lambda: (self.library.delete(track["id"]), self.library.refresh()),
                       lambda *_: self.page.reload_library())

    # ── this computer as a device ──────────────────────────────────────────
    def item(self, path):
        track = self.page.library.get(path)
        cloud = self.track_of(path) if self.is_cloud(path) else None
        title = (track.title if track else "") or ""
        artist = (track.artist if track else "") or ""
        duration = (track.duration if track else 0) or 0
        return {"key": songkeys.song_key(artist, title, duration), "cloudId": cloud["id"] if cloud else 0, "title": title,
                "artist": artist, "album": (track.album if track else "") or "", "durationMs": int(duration * 1000)}

    def publish_soon(self, delay_ms=1500):
        if not self.account.signed_in:
            return
        if self._publish_timer:
            GLib.source_remove(self._publish_timer)
        self._publish_timer = GLib.timeout_add(delay_ms, self._publish)

    def _publish(self):
        self._publish_timer = None
        page = self.page
        state = None
        if page.current_path:
            queue = page.queue.items()
            index = max(0, page.queue.index)
            start = max(0, index - 50)
            position, duration = page.player.position()
            state = {"item": self.item(page.current_path), "positionMs": int(position * 1000),
                     "durationMs": int((duration or 0) * 1000), "playing": page.player.playing,
                     "index": index - start, "at": int(time.time() * 1000),
                     "queue": [self.item(p) for p in queue[start:index + 250]]}
        body = {"device": self.account.device, "name": self.account.device_name, "platform": "linux", "state": state}
        background(lambda: self.api.post("/api/player/state", body), lambda *_: None)
        return False

    def _publish_while_playing(self):
        if self.page.player.playing:
            self.publish_soon(0)
        return True

    def _listen(self):
        """Long poll for the remote control's commands (being here also says "online")."""
        failures = 0
        while not self._stop.is_set():
            if not self.account.signed_in:
                time.sleep(10)
                continue
            try:
                answer = self.api.get(f"/api/player/commands?device={self.account.device}&platform=linux&wait=25&name="
                                      + GLib.uri_escape_string(self.account.device_name, None, False), timeout=40)
                failures = 0
            except Exception:  # noqa: BLE001 (offline: try again, slower each time)
                failures += 1
                time.sleep(min(60, 2 * failures))
                continue
            for command in answer.get("commands", []):
                GLib.idle_add(self._run, command)

    def _run(self, command):
        page, args = self.page, command.get("args") or {}
        action = command.get("action")
        if action == "refresh":
            return False
        if action in ("play", "pause", "toggle"):
            if action == "toggle" or (action == "play") != page.player.playing:
                page.toggle()
        elif action == "next":
            page.next()
        elif action == "previous":
            page.previous()
        elif action == "seek" and "positionMs" in args:
            page.jump(float(args["positionMs"]) / 1000)
        elif action == "playQueue":
            self.play_items(args.get("items") or [], int(args.get("index") or 0), float(args.get("positionMs") or 0) / 1000,
                            args.get("play", True) is not False)
        self.publish_soon(500)
        return False

    def play_items(self, items, index, position, play=True):
        """Plays a queue from another device: this computer's files first, else the cloud's."""
        matcher = songkeys.Matcher(self._songs())
        chosen = []
        for item in items:
            found = matcher.find(item.get("key") or "")
            if found:
                chosen.append(found["path"])
            elif item.get("cloudId"):
                chosen.append(self.library.downloaded(item["cloudId"]) or self.library.audio_url(item["cloudId"]))
        if not chosen:
            return False
        index = max(0, min(index, len(chosen) - 1))
        self.page.play_paths(chosen, index)
        if position > 1:
            GLib.timeout_add(700, lambda: self.page.jump(position) and False)
        if not play:
            GLib.timeout_add(800, lambda: self.page.player.playing and self.page.toggle() and False)
        return True

    # ── other devices ──────────────────────────────────────────────────────
    def refresh_devices(self, done=None):
        if not self.account.signed_in:
            return

        def work():
            return [d for d in self.api.get("/api/player/devices").get("devices", []) if d["device"] != self.account.device]

        def finish(devices, error):
            if devices is not None:
                self.devices = devices
                self._notify()
            if done:
                done(devices)
        background(work, finish)

    def command(self, device, action, args=None):
        body = {"target": device["device"], "device": self.account.device, "action": action, "args": args or {}}
        background(lambda: self.api.post("/api/player/command", body),
                   lambda *_: GLib.timeout_add(1200, lambda: self.refresh_devices() and False))

    def continue_here(self, device):
        state = device.get("state") or {}
        position = (state.get("positionMs") or 0) / 1000
        if state.get("playing"):
            position += max(0, time.time() - (device.get("updated") or 0) / 1000)
        if self.play_items(state.get("queue") or [], state.get("index") or 0, position):
            self.command(device, "pause")
            return True
        return False

    def play_there(self, device):
        page = self.page
        queue = page.queue.items()
        if not queue:
            return
        index = max(0, page.queue.index)
        start = max(0, index - 50)
        self.command(device, "playQueue", {"items": [self.item(p) for p in queue[start:index + 250]], "index": index - start,
                                           "positionMs": int(page.player.position()[0] * 1000)})
        if page.player.playing:
            page.toggle()

    def continue_offer(self):
        """Another device's recent song, when this computer is not playing."""
        if self.page.player.playing:
            return None
        for device in sorted(self.devices, key=lambda d: d.get("updated") or 0, reverse=True):
            state = device.get("state") or {}
            if state.get("item") and time.time() - (device.get("updated") or 0) / 1000 < 12 * 3600:
                return device
        return None

    def _set_theme(self, theme_id):
        app = self.page.window.get_application() if hasattr(self.page.window, "get_application") else None
        manager = getattr(app, "themes", None)
        if manager is not None and hasattr(manager, "set_theme"):
            GLib.idle_add(lambda: manager.set_theme(theme_id) and False)
        else:
            self.page.store.set_setting("music.theme", theme_id)

    def _notify(self):
        for callback in list(self.listeners):
            callback()

    def close(self):
        self._stop.set()
