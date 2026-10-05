"""The song identity shared with the phone and the server: artist and title folded (no accents, lower case,
single spaces) and whole seconds — "seu pereira e coletivo 401|obsoleto|223". Same song: artist and title equal,
seconds 3 apart at most. Records *about* a song (like, lyrics, plays) use "artist|title" only."""
import re
import unicodedata

SPACES = re.compile(r"\s+")


def norm(text):
    text = unicodedata.normalize("NFKD", str(text or ""))
    text = "".join(char for char in text if not unicodedata.combining(char)).lower()
    return SPACES.sub(" ", text).strip()


def song_key(artist, title, duration_seconds):
    return f"{norm(artist)}|{norm(title)}|{int(duration_seconds or 0)}"


def split(key):
    """("artist|title", seconds), or None for a key without seconds."""
    bar = key.rfind("|")
    if bar <= 0 or key.find("|") == bar:
        return None
    try:
        return key[:bar], int(key[bar + 1:])
    except ValueError:
        return None


def name(key):
    parts = split(key)
    return parts[0] if parts else key


class Matcher:
    """Finds this computer's track for a key: its own file first, else the cloud's."""

    def __init__(self, entries):
        self.by_name = {}
        for item, key, in_cloud in entries:
            parts = split(key)
            if parts:
                self.by_name.setdefault(parts[0], []).append((item, parts[1], in_cloud))

    def find(self, key):
        parts = split(key)
        if parts is None:
            options = self.by_name.get(key) or []
            return min(options, key=lambda o: o[2])[0] if options else None
        name_, seconds = parts
        options = [o for o in self.by_name.get(name_) or [] if abs(o[1] - seconds) <= 3]
        return min(options, key=lambda o: (o[2], abs(o[1] - seconds)))[0] if options else None
