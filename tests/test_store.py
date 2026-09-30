from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch

from ayo_musica import migrations
from ayo_musica.store import Store, import_legacy


class MigrationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "desk.sqlite3"

    def tearDown(self):
        self.temp.cleanup()

    def version(self):
        with sqlite3.connect(self.path) as db:
            return db.execute("PRAGMA user_version").fetchone()[0]

    def test_new_database_reaches_latest_version(self):
        Store(self.path).close()
        self.assertEqual(self.version(), len(migrations.MIGRATIONS))
        Store(self.path).close()
        self.assertEqual(self.version(), len(migrations.MIGRATIONS))

    def test_database_from_0_1_2_keeps_its_data(self):
        with sqlite3.connect(self.path) as db:
            db.executescript(migrations.MIGRATIONS[0])
            db.execute("INSERT INTO events(day,time,title) VALUES('2026-09-20','10:00','Consulta')")
            db.execute("INSERT INTO music_library(id,folder) VALUES(1,'/musicas')")
            db.execute("INSERT INTO music_folder_tracks(path) VALUES('/musicas/a.mp3')")
        store = Store(self.path)
        try:
            tables = {r[0] for r in store.db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            self.assertNotIn("events", tables, "O calendário da suíte não vem para o app de música")
            self.assertEqual(store.music_folder(), "/musicas")
            self.assertEqual(store.tracks(), ["/musicas/a.mp3"])
            store.db.execute("SELECT path, plays FROM music_stats").fetchall()
        finally:
            store.close()
        self.assertEqual(self.version(), len(migrations.MIGRATIONS))

    def test_failed_migration_rolls_back_completely(self):
        Store(self.path).close()
        broken = (*migrations.MIGRATIONS, "CREATE TABLE half_done(x); SELECT * FROM missing_table;")
        with patch.object(migrations, "MIGRATIONS", broken):
            with self.assertRaises(sqlite3.OperationalError):
                Store(self.path)
        self.assertEqual(self.version(), len(migrations.MIGRATIONS))
        with sqlite3.connect(self.path) as db:
            tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        self.assertNotIn("half_done", tables)

    def test_newer_database_is_not_downgraded(self):
        with sqlite3.connect(self.path) as db:
            db.execute(f"PRAGMA user_version = {len(migrations.MIGRATIONS) + 1}")
        with self.assertRaises(RuntimeError):
            Store(self.path)

    def test_settings_round_trip_json_values(self):
        store = Store(self.path)
        try:
            self.assertEqual(store.setting("music.volume", 70), 70)
            store.set_setting("music.volume", 45)
            store.set_setting("music.eq", {"preset": "Rock", "bands": [3, 2, 0]})
        finally:
            store.close()
        store = Store(self.path)
        try:
            self.assertEqual(store.setting("music.volume"), 45)
            self.assertEqual(store.setting("music.eq")["bands"], [3, 2, 0])
        finally:
            store.close()


class LegacyImportTests(unittest.TestCase):
    """The first run copies the library of Ayo Desk's Música; the old files are never changed."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.old = self.root / "ayo-desk" / "desk.sqlite3"
        self.old.parent.mkdir()
        with sqlite3.connect(self.old) as db:
            for script in migrations.MIGRATIONS[:6]:
                db.executescript(script)
            db.execute("PRAGMA user_version=6")
            db.execute("INSERT INTO events(day,time,title) VALUES('2026-09-20','10:00','Consulta')")
            db.execute("INSERT INTO music_library(id,folder) VALUES(1,'/home/ayo/Music')")
            db.execute("INSERT INTO playlists(name, created, updated) VALUES('Favoritas', 'x', 'x')")
            db.execute("INSERT INTO settings(key,value) VALUES('music.volume','55'),('desk.tab','\"x\"')")
        db.close()
        self.old_covers = self.root / "old-covers"
        self.old_covers.mkdir()
        (self.old_covers / "abc.png").write_bytes(b"png")

    def tearDown(self):
        self.tmp.cleanup()

    def test_library_playlists_settings_and_covers_come_along(self):
        before = self.old.read_bytes()
        target, covers = self.root / "new" / "musica.sqlite3", self.root / "new-covers"
        target.parent.mkdir()
        found = import_legacy(target, [(self.root / "nada.sqlite3", self.root / "x"), (self.old, self.old_covers)],
                              covers)
        self.assertEqual(found, self.old)
        store = Store(target)
        try:
            self.assertEqual(store.music_folder(), "/home/ayo/Music")
            self.assertEqual(store.setting("music.volume"), 55)
            self.assertIsNone(store.setting("desk.tab"))
            self.assertEqual(store.db.execute("SELECT name FROM playlists").fetchone()[0], "Favoritas")
        finally:
            store.close()
        self.assertEqual((covers / "abc.png").read_bytes(), b"png")
        self.assertEqual(self.old.read_bytes(), before, "O banco antigo não pode mudar")

    def test_nothing_to_import(self):
        self.assertIsNone(import_legacy(self.root / "novo.sqlite3", [(self.root / "nada.sqlite3", self.root)]))


if __name__ == "__main__":
    unittest.main()


class StoreTracksTests(unittest.TestCase):
    def test_added_files_are_deduplicated_and_never_deleted(self):
        with tempfile.TemporaryDirectory() as folder:
            store = Store(Path(folder) / "data.sqlite3")
            music = Path(folder) / "song ' with spaces.ogg"
            music.write_bytes(b"sample")
            store.add_tracks([music, music])
            self.assertEqual(store.tracks(), [str(music)])
            store.remove_track(str(music))
            self.assertEqual(store.tracks(), [])
            self.assertTrue(music.exists())
            store.close()
