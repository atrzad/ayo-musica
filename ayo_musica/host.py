"""Programs the player can use when installed: inside its own environment first and, when running as
a Flatpak, on the host system too (cava, songrec, whisper-cpp, pactl), through `flatpak-spawn --host`."""
from functools import lru_cache
import shutil
import subprocess

from . import paths


@lru_cache(maxsize=None)
def _on_host(name):
    try:
        result = subprocess.run(["flatpak-spawn", "--host", "sh", "-c", f'command -v "{name}"'],
                                capture_output=True, text=True, timeout=5, check=False)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return result.stdout.strip() or None if result.returncode == 0 else None


def command(*names):
    """The argv prefix that runs the first of `names` found, or None: ["cava"] or
    ["flatpak-spawn", "--host", "/usr/bin/cava"]."""
    for name in names:
        found = shutil.which(name)
        if found:
            return [found]
    if paths.FLATPAK:
        for name in names:
            found = _on_host(name)
            if found:
                # --watch-bus: the host program ends when the app does (or when it is killed)
                return ["flatpak-spawn", "--host", "--watch-bus", found]
    return None


def shared_dir():
    """A folder that both the app and host programs can read and write (Flatpak's /tmp is private)."""
    folder = paths.cache_dir() / "host" if paths.FLATPAK else None
    if folder is not None:
        folder.mkdir(parents=True, exist_ok=True)
    return folder
