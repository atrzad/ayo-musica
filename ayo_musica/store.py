"""The app's SQLite database: settings and the music folder. MusicDB (db.py) adds the library on top."""
import json
import os
from pathlib import Path
import shutil
import sqlite3

from . import paths
from .migrations import migrate

DATABASE = "musica.sqlite3"


def import_legacy(target, sources=None, covers_target=None):
    """First run: copy the library, playlists, statistics and tag backups of an earlier install
    (Ayo Desk's Música), with the cover images the backups point to. Returns the source or None."""
    for database, covers in sources if sources is not None else paths.legacy_sources():
        if not database.is_file() or database.resolve() == Path(target).resolve():
            continue
        try:
            source = sqlite3.connect(f"{database.resolve().as_uri()}?mode=ro", uri=True)
            destination = sqlite3.connect(target)
            with destination:
                source.backup(destination)
            source.close()
            destination.close()
        except sqlite3.Error:
            Path(target).unlink(missing_ok=True)
            continue
        covers_target = Path(covers_target or paths.cache_dir() / "covers")
        if covers.is_dir() and covers.resolve() != covers_target.resolve():
            def link_or_copy(src, dst):
                try:
                    os.link(src, dst)  # same disk: instant, no extra space
                except OSError:
                    shutil.copy2(src, dst)
            shutil.copytree(covers, covers_target, copy_function=link_or_copy, dirs_exist_ok=True)
        return database
    return None


class Store:
    def __init__(self, path=None):
        self.imported = None
        if path is None:
            base = paths.data_dir()
            base.mkdir(parents=True, exist_ok=True, mode=0o700)
            path = base / DATABASE
            if not path.exists():
                self.imported = import_legacy(path)
        self.db = sqlite3.connect(path)
        if str(path) != ":memory:":
            os.chmod(path, 0o600)
        self.db.row_factory = sqlite3.Row
        migrate(self.db)

    def setting(self, key, default=None):
        record = self.db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return json.loads(record[0]) if record else default

    def set_setting(self, key, value):
        with self.db:
            self.db.execute("INSERT INTO settings(key,value) VALUES(?,?) "
                            "ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, json.dumps(value)))

    def add_tracks(self, paths_):
        paths_ = [(str(Path(p).resolve()),) for p in paths_]
        with self.db:
            self.db.executemany("INSERT OR IGNORE INTO tracks(path) VALUES(?)", paths_)
            self.db.executemany("DELETE FROM music_exclusions WHERE path=?", paths_)

    def tracks(self):
        manual = [r[0] for r in self.db.execute("SELECT path FROM tracks ORDER BY id")]
        folder = [r[0] for r in self.db.execute(
            "SELECT path FROM music_folder_tracks WHERE path NOT IN "
            "(SELECT path FROM music_exclusions) ORDER BY path COLLATE NOCASE")]
        return list(dict.fromkeys([*manual, *folder]))

    def music_folder(self):
        record = self.db.execute("SELECT folder FROM music_library WHERE id=1").fetchone()
        return record[0] if record else None

    def sync_music_folder(self, folder, paths_):
        """Commit only complete scans; retain manually added files across folder changes."""
        folder = str(Path(folder).resolve())
        with self.db:
            if self.music_folder() != folder:
                self.db.execute("DELETE FROM music_exclusions")
            self.db.execute("INSERT INTO music_library(id,folder) VALUES(1,?) "
                            "ON CONFLICT(id) DO UPDATE SET folder=excluded.folder", (folder,))
            self.db.execute("DELETE FROM music_folder_tracks")
            self.db.executemany("INSERT OR IGNORE INTO music_folder_tracks(path) VALUES(?)",
                                ((str(p),) for p in paths_))

    def remove_track(self, path):
        with self.db:
            self.db.execute("DELETE FROM tracks WHERE path=?", (path,))
            self.db.execute("INSERT OR IGNORE INTO music_exclusions(path) "
                            "SELECT path FROM music_folder_tracks WHERE path=?", (path,))

    def close(self):
        self.db.close()
