"""The cloud library on this computer: the server's track list (asked again only for what changed), covers,
the songs downloaded for listening offline, and sending this computer's songs up."""
import hashlib
import json
import mimetypes
import os
import threading
import urllib.parse

from . import keys as songkeys


class CloudLibrary:
    def __init__(self, api, folder):
        self.api = api
        self.folder = folder
        self.downloads = os.path.join(folder, "downloads")
        self.covers = os.path.join(folder, "covers")
        os.makedirs(self.downloads, exist_ok=True)
        os.makedirs(self.covers, exist_ok=True)
        self.cache_file = os.path.join(folder, "tracks.json")
        self.lock = threading.Lock()
        self.transfers = {}  # track id or path → 0..1
        try:
            with open(self.cache_file, encoding="utf-8") as handle:
                self.cache = json.load(handle)
        except (OSError, ValueError):
            self.cache = {"rev": 0, "server": "", "tracks": {}}
        self._index()
        self.cover_keys_file = os.path.join(folder, "cover-keys.json")
        try:
            with open(self.cover_keys_file, encoding="utf-8") as handle:
                self.cover_keys = json.load(handle)
        except (OSError, ValueError):
            self.cover_keys = {}

    @property
    def tracks(self):
        return list(self.cache["tracks"].values())

    def _index(self):
        """What the window asks many times a second: a track by its address, and which ones are downloaded
        (kept in memory: looking through 1500 tracks and the disk each time froze the window)."""
        self.saved = {}
        for name in os.listdir(self.downloads):
            stem, _dot, _ext = name.partition(".")
            if stem.isdigit() and not name.endswith(".part"):
                self.saved[int(stem)] = os.path.join(self.downloads, name)
        self.by_path = {}
        for track in self.cache["tracks"].values():
            self.by_path[self.audio_url(track["id"])] = track
            if track["id"] in self.saved:
                self.by_path[self.saved[track["id"]]] = track

    def track_at(self, path):
        return self.by_path.get(str(path))

    def refresh(self):
        server = self.api.account.server
        cache = self.cache if self.cache.get("server") == server else {"rev": 0, "server": server, "tracks": {}}
        tracks = dict(cache["tracks"])
        rev = cache["rev"]
        for _ in range(100):
            answer = self.api.get(f"/api/tracks?since={rev}")
            for track in answer.get("tracks", []):
                if track.get("deleted"):
                    tracks.pop(str(track["id"]), None)
                else:
                    tracks[str(track["id"])] = track
            rev = answer.get("rev", rev)
            if not answer.get("more"):
                break
        with self.lock:
            self.cache = {"rev": rev, "server": server, "tracks": tracks}
            self._index()
            temp = f"{self.cache_file}.tmp"
            with open(temp, "w", encoding="utf-8") as handle:
                json.dump(self.cache, handle, ensure_ascii=False)
            os.replace(temp, self.cache_file)

    def clear(self):
        self.cache = {"rev": 0, "server": "", "tracks": {}}
        self._index()
        try:
            os.remove(self.cache_file)
        except OSError:
            pass

    def audio_url(self, track_id):
        return f"{self.api.account.server}/api/tracks/{track_id}/audio"

    def downloaded(self, track_id):
        return self.saved.get(int(track_id))

    def _album(self, track):
        """One picture per album (shared by its tracks); a track without an album has its own."""
        album = track.get("album") or ""
        owner = track.get("albumArtist") or track.get("artist") or ""
        return hashlib.sha1(f"{songkeys.norm(owner)}|{songkeys.norm(album)}".encode()).hexdigest()[:20] if album \
            else f"track-{track['id']}"

    def cover(self, track):
        """The cover-cache key (covers.py) of the track's album, once fetched."""
        return self.cover_keys.get(self._album(track), "")

    def fetch_covers(self):
        """The albums' covers, in the background, once each (a few hundred pictures, not one per song), into the app's
        cover cache."""
        from .. import covers
        wanted = {}
        for track in self.tracks:
            if track.get("cover"):
                wanted.setdefault(self._album(track), track)
        fetched = 0
        for album, track in wanted.items():
            if album in self.cover_keys:
                continue
            temp = os.path.join(self.covers, f".{album}")
            try:
                if not self.api.download(f"/api/tracks/{track['id']}/cover", temp):
                    continue
                with open(temp, "rb") as handle:
                    key = covers.store(handle.read())
            except Exception:  # noqa: BLE001 (a missing cover is not an error)
                continue
            finally:
                if os.path.exists(temp):
                    os.remove(temp)
            if key:
                self.cover_keys[album] = key
                fetched += 1
        if fetched:
            temp = f"{self.cover_keys_file}.tmp"
            with open(temp, "w", encoding="utf-8") as handle:
                json.dump(self.cover_keys, handle)
            os.replace(temp, self.cover_keys_file)
        return fetched

    def row(self, track):
        """A cloud track as a library row (the "path" is its address; the downloaded file plays when there is one)."""
        path = self.downloaded(track["id"]) or self.audio_url(track["id"])
        title, artist = track.get("title") or "Sem título", track.get("artist") or ""
        return {
            "path": path, "title": title, "artist": artist, "album": track.get("album") or "",
            "album_artist": track.get("albumArtist") or "", "genre": track.get("genre") or "", "year": track.get("year") or None,
            "track_no": track.get("track") or None, "disc_no": track.get("disc") or None,
            "duration": (track.get("durationMs") or 0) / 1000, "cover": self.cover(track), "plays": 0, "skips": 0,
            "favorite": 0, "rating": 0, "added_at": None, "search": songkeys.norm(f"{title} {artist} {track.get('album') or ''}"),
            "codec": (track.get("mime") or "").split("/")[-1].upper(), "size": track.get("size") or 0,
            "cloud_id": track["id"],
        }

    def download(self, track, progress=None):
        ext = {"audio/flac": "flac", "audio/mp4": "m4a", "audio/ogg": "ogg", "audio/wav": "wav"}.get(track.get("mime"), "mp3")
        target = os.path.join(self.downloads, f"{track['id']}.{ext}")
        self.transfers[track["id"]] = 0.0
        try:
            ok = self.api.download(f"/api/tracks/{track['id']}/audio", target,
                                   lambda got, total: self.transfers.__setitem__(track["id"], got / total if total else 0))
            if ok:
                self.saved[int(track["id"])] = target
                self.by_path[target] = track
            return ok
        finally:
            self.transfers.pop(track["id"], None)

    def remove_download(self, track_id):
        path = self.saved.pop(int(track_id), None)
        if path:
            self.by_path.pop(path, None)
            if os.path.exists(path):
                os.remove(path)

    def upload(self, path):
        """Sends a file of this computer (skipped when the same file is already in the cloud)."""
        digest = hashlib.sha256()
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1 << 20), b""):
                digest.update(chunk)
        try:
            self.api.get(f"/api/tracks/sha/{digest.hexdigest()}")
            return False  # already there
        except Exception:  # noqa: BLE001 (404: not there yet)
            pass
        self.transfers[path] = 0.0
        try:
            self.api.upload("/api/tracks/upload", path, mimetypes.guess_type(path)[0] or "audio/mpeg",
                            {"X-Filename": urllib.parse.quote(os.path.basename(path))},
                            progress=lambda sent, size: self.transfers.__setitem__(path, sent / size if size else 0))
            return True
        finally:
            self.transfers.pop(path, None)

    def delete(self, track_id):
        self.api.delete(f"/api/tracks/{track_id}")
        self.remove_download(track_id)
