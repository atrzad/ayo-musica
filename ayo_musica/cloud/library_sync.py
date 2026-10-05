"""The desktop's side of sync: playlists, likes, plays, lyrics and the theme as records, and back."""
import datetime as dt
import json

from . import keys as songkeys
from .sync import record_id

CHOSEN = ("escolhida", "manual", "voz", "transcrita")


def ms(stamp):
    try:
        return int(dt.datetime.fromisoformat(stamp).timestamp() * 1000)
    except (TypeError, ValueError):
        return 0


class DesktopSync:
    """songs(): [(row, key, in_cloud)] with row["path"]; the page's music store does the writing."""

    def __init__(self, music, store, songs, device, set_theme, engine=None):
        self.music = music
        self.store = store
        self.songs = songs
        self.device = device
        self.set_theme = set_theme
        self.engine = engine  # set after the engine is made (for the last agreed values)

    def manages(self, kind, key):
        return kind in ("playlist", "flags", "lyrics") or (kind == "plays" and key.endswith(f"@{self.device()}")) or \
            (kind == "pref" and key == "theme")

    def export(self, matcher):
        entries = self.songs()
        key_of = {row["path"]: key for row, key, _ in entries}
        out = {}
        for playlist in self.music.sync_playlists():
            local = [key_of[path] for path in playlist["paths"] if path in key_of]
            remote = json.loads(playlist["remote_keys"] or "[]")
            resolved = [found["path"] for found in (matcher.find(key) for key in remote) if found]
            songs = remote if remote and resolved == playlist["paths"] else local + [k for k in remote if not matcher.find(k)]
            out[record_id("playlist", playlist["uuid"])] = {"name": playlist["name"], "description": playlist["description"],
                                                              "songs": songs}
        favorites = set()
        for stat in self.music.sync_stats():
            key = key_of.get(stat["path"])
            if not key:
                continue
            name = songkeys.name(key)
            if stat["favorite"]:
                favorites.add(name)
            if stat["plays"] or stat["skips"]:
                out[record_id("plays", f"{name}@{self.device()}")] = {"plays": stat["plays"], "skips": stat["skips"],
                                                                     "lastPlayed": ms(stat["last_played"])}
        # Likes: this computer's, keeping the phone's "fora do aleatório" of songs here that are not liked.
        names_here = {songkeys.name(key) for key in key_of.values()}
        for rid, value in (self.engine.state["base"].items() if self.engine else []):
            kind, name = rid.split("\u0000", 1)
            if kind == "flags" and name in names_here and name not in favorites and isinstance(value, dict) and value.get("noShuffle"):
                out[rid] = {"favorite": False}
        for name in favorites:
            out[record_id("flags", name)] = {"favorite": True}
        for row in self.music.chosen_lyrics(CHOSEN):
            key = key_of.get(row["path"])
            if key:
                out[record_id("lyrics", songkeys.name(key))] = {
                    "synced": row["text"] if row["synced"] else "", "plain": "" if row["synced"] else row["text"],
                    "source": row["source"], "offsetMs": row["offset_ms"] or 0}
        theme = self.store.setting("music.theme")
        if theme:
            out[record_id("pref", "theme")] = theme
        return out

    def apply(self, kind, key, value, matcher):
        if kind == "playlist":
            if value is None:
                self.music.delete_synced_playlist(key)
                return True
            keys = [k for k in value.get("songs") or [] if isinstance(k, str)]
            paths = [found["path"] for found in (matcher.find(k) for k in keys) if found]
            self.music.put_synced_playlist(key, value.get("name") or "Playlist", value.get("description") or "", paths, keys)
            return True
        if kind == "pref":
            if key == "theme" and isinstance(value, str):
                self.set_theme(value)
            return True
        found = matcher.find(key)
        if found is None:
            return False
        path = found["path"]
        if kind == "flags":
            self.music.set_favorite(path, bool(value and value.get("favorite")))
        elif kind == "lyrics":
            if value:
                synced = bool(value.get("synced"))
                self.music.save_lyrics(path, value.get("source") or "escolhida", synced,
                                       value.get("synced") if synced else value.get("plain") or "")
                self.music.set_lyrics_offset(path, int(value.get("offsetMs") or 0))
        return True
