"""Where the app keeps its data and cache on each system (Linux/XDG, Flatpak, Windows)."""
import os
from pathlib import Path
import sys

APP = "ayo-musica"
WINDOWS = sys.platform == "win32"
LINUX = sys.platform.startswith("linux")
FLATPAK = bool(os.environ.get("FLATPAK_ID"))


def _local_appdata():
    return Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")


def data_dir():
    """Library database, settings, voice models: ~/.local/share/ayo-musica (in Flatpak, ~/.var/app/...)."""
    if os.environ.get("AYO_DATA_DIR"):  # tests and self-checks (Flatpak always sets XDG_DATA_HOME)
        return Path(os.environ["AYO_DATA_DIR"])
    if os.environ.get("XDG_DATA_HOME"):
        return Path(os.environ["XDG_DATA_HOME"]) / APP
    return _local_appdata() / APP if WINDOWS else Path.home() / ".local" / "share" / APP


def cache_dir():
    """Covers and web answers: ~/.cache/ayo-musica (in Flatpak, ~/.var/app/...)."""
    if os.environ.get("AYO_CACHE_DIR"):
        return Path(os.environ["AYO_CACHE_DIR"])
    if os.environ.get("XDG_CACHE_HOME"):
        return Path(os.environ["XDG_CACHE_HOME"]) / APP
    return _local_appdata() / APP / "cache" if WINDOWS else Path.home() / ".cache" / APP


def legacy_sources():
    """Earlier installs whose library can be imported on first run: (database, covers folder).

    The real home paths, also inside Flatpak (which gets read-only access to them)."""
    if os.environ.get("AYO_NO_IMPORT"):
        return []
    home = Path.home()
    return [(home / ".local/share/ayo-musica/musica.sqlite3", home / ".cache/ayo-musica/covers"),
            (home / ".local/share/ayo-desk/desk.sqlite3", home / ".cache/ayo-desk/covers")]
