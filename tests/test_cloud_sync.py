"""Account sync on the desktop: the shared song keys, the engine's rules and the real playlists/likes database."""
from pathlib import Path
import tempfile
import unittest

from ayo_musica.cloud import keys
from ayo_musica.cloud.library_sync import DesktopSync
from ayo_musica.cloud.sync import SyncEngine, dumps, record_id
from ayo_musica.db import MusicDB
from ayo_musica.store import Store


class FakeServer:
    """The server's rule in memory: newest "at" wins (here: order of arrival), changes numbered by rev."""

    def __init__(self):
        self.rows, self.rev = {}, 0

    def handle(self, device, since, changes):
        for change in changes:
            self.rev += 1
            self.rows[record_id(change["kind"], change["key"])] = dict(change, device=device, rev=self.rev)
        out = sorted((r for r in self.rows.values() if r["rev"] > since), key=lambda r: r["rev"])
        return {"rev": self.rev, "more": False, "changes": [
            {"kind": r["kind"], "key": r["key"], "value": r["value"], "deleted": r["deleted"], "device": r["device"]} for r in out]}


class KeysTests(unittest.TestCase):
    def test_same_keys_as_the_phone_and_the_server(self):
        # The phone's SyncEngineTest and the server's songkey.js give these exact strings.
        self.assertEqual(keys.song_key("Seu  Pereira e Coletivo 401", "Obsoleto", 223.8), "seu pereira e coletivo 401|obsoleto|223")
        self.assertEqual(keys.name("chico buarque|construcao|384"), "chico buarque|construcao")
        matcher = keys.Matcher([({"path": "cloud"}, "chico buarque|construcao|383", True),
                                ({"path": "/m/c.mp3"}, keys.song_key("Chico Buarque", "Construção", 384), False)])
        self.assertEqual(matcher.find("chico buarque|construcao|386")["path"], "/m/c.mp3")
        self.assertIsNone(matcher.find("chico buarque|construcao|392"))
        self.assertEqual(matcher.find("chico buarque|construcao")["path"], "/m/c.mp3")


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.server = FakeServer()

    def tearDown(self):
        self.temp.cleanup()

    def engine(self, name, hooks):
        return SyncEngine(str(Path(self.temp.name) / f"{name}.json"), hooks, lambda: name,
                          lambda since, changes: self.server.handle(name, since, changes))

    def test_keeps_fields_and_records_it_does_not_manage(self):
        class Phone:
            records = {record_id("flags", "a|b"): {"favorite": True, "noShuffle": True},
                       record_id("fix", "a|b"): {"title": "B"}}
            def songs(self): return []
            def export(self, matcher): return dict(self.records)
            def apply(self, *args): return True
            def manages(self, kind, key): return True
        self.engine("celular", Phone()).sync()

        class Desktop:
            liked = True
            def songs(self): return [({"path": "/b.mp3"}, "a|b|100", False)]
            def export(self, matcher): return {record_id("flags", "a|b"): {"favorite": self.liked}}
            def apply(self, *args): return True
            def manages(self, kind, key): return kind != "fix"
        desktop = Desktop()
        pc = self.engine("pc", desktop)
        pc.sync()
        desktop.liked = False  # unliked here: the phone's "fora do aleatório" stays
        self.assertEqual(pc.sync()["sent"], 1)
        flags = self.server.rows[record_id("flags", "a|b")]["value"]
        self.assertEqual(flags, {"favorite": False, "noShuffle": True})
        self.assertFalse(self.server.rows[record_id("fix", "a|b")]["deleted"])  # not the desktop's to delete
        self.assertEqual(pc.sync()["sent"], 0)
        self.assertEqual(dumps({"b": 1, "a": 2}), '{"a":2,"b":1}')


class DesktopDatabaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = Store(Path(self.temp.name) / "musica.sqlite3")
        self.music = MusicDB(self.store)
        self.rows = [{"path": "/m/oceano.mp3", "artist": "Djavan", "title": "Oceano", "duration": 224.0},
                     {"path": "/m/lucro.mp3", "artist": "BaianaSystem", "title": "Lucro", "duration": 200.0}]

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def sync(self, theme=None):
        songs = lambda: [(row, keys.song_key(row["artist"], row["title"], row["duration"]), False) for row in self.rows]  # noqa: E731
        return DesktopSync(self.music, self.store, songs, lambda: "pc", lambda t: self.store.set_setting("music.theme", t))

    def test_playlists_likes_and_lyrics_round_trip(self):
        playlist = self.music.create_playlist("Viagem", ["/m/oceano.mp3"])
        self.music.set_favorite("/m/lucro.mp3", True)
        self.music.save_lyrics("/m/oceano.mp3", "voz", True, "[00:10.00]Assim que o dia amanheceu")
        hooks = self.sync()
        matcher = keys.Matcher(hooks.songs())
        out = hooks.export(matcher)
        uuid = self.music.sync_playlists()[0]["uuid"]
        self.assertEqual(out[record_id("playlist", uuid)]["songs"], ["djavan|oceano|224"])
        self.assertEqual(out[record_id("flags", "baianasystem|lucro")], {"favorite": True})
        self.assertTrue(out[record_id("lyrics", "djavan|oceano")]["synced"].startswith("[00:10.00]"))
        # A playlist from the phone, with a song this computer does not have: kept, and sent back as it came.
        hooks.apply("playlist", "p-phone", {"name": "Do celular", "songs": ["djavan|oceano|225", "sant|prantos|126"]}, matcher)
        synced = [p for p in self.music.sync_playlists() if p["uuid"] == "p-phone"][0]
        self.assertEqual(synced["paths"], ["/m/oceano.mp3"])
        self.assertEqual(hooks.export(matcher)[record_id("playlist", "p-phone")]["songs"], ["djavan|oceano|225", "sant|prantos|126"])
        hooks.apply("playlist", "p-phone", None, matcher)
        self.assertNotIn("p-phone", [p["uuid"] for p in self.music.sync_playlists()])
        self.assertTrue(hooks.apply("flags", "djavan|oceano", {"favorite": True}, matcher))
        self.assertFalse(hooks.apply("flags", "sant|prantos", {"favorite": True}, matcher))  # held for later
        self.assertEqual(self.music.playlist_paths(playlist), ["/m/oceano.mp3"])


if __name__ == "__main__":
    unittest.main()
