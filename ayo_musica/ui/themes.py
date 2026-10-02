"""Color themes: the palettes in data/themes/themes.json (shared with the Android app) and one that follows
the wallpaper through wallust (~/.cache/wallust/colors.css), updated live when the wallpaper changes.

"Preto e branco" keeps libadwaita's own surfaces and only makes the accents achromatic, like before.
"""
import json
from pathlib import Path
import re

from gi.repository import Adw, Gdk, Gio, Gtk

MONO = "mono"
WALLUST = "wallust"
WALLUST_FILE = Path.home() / ".cache" / "wallust" / "colors.css"
SCHEMES = (("auto", "Automático", Adw.ColorScheme.DEFAULT), ("light", "Claro", Adw.ColorScheme.FORCE_LIGHT),
           ("dark", "Escuro", Adw.ColorScheme.FORCE_DARK))


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))["themes"]


# ── color math ─────────────────────────────────────────────────────────────
def rgb(color):
    color = color.lstrip("#")
    return tuple(int(color[i:i + 2], 16) for i in (0, 2, 4))


def hex_color(values):
    return "#" + "".join(f"{max(0, min(255, round(v))):02X}" for v in values)


def mix(a, b, amount):
    """`amount` of b over a."""
    return hex_color(x + (y - x) * amount for x, y in zip(rgb(a), rgb(b)))


def luminance(color):
    channels = [v / 255 for v in rgb(color)]
    channels = [c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4 for c in channels]
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]


def contrast(a, b):
    high, low = sorted((luminance(a), luminance(b)), reverse=True)
    return (high + 0.05) / (low + 0.05)


def saturation(color):
    values = [v / 255 for v in rgb(color)]
    high, low = max(values), min(values)
    return 0 if high == 0 else (high - low) / high


# ── wallust ────────────────────────────────────────────────────────────────
DEFINE = re.compile(r"@define-color\s+(\w+)\s+(#[0-9A-Fa-f]{6})\s*;")


def wallust_palette(path=WALLUST_FILE):
    """bg/fg from wallust, accent = the most colorful of its colors that still stands out from the background."""
    try:
        colors = dict(DEFINE.findall(Path(path).read_text(encoding="utf-8")))
    except OSError:
        return None
    bg, fg = colors.get("background"), colors.get("foreground")
    if not bg or not fg:
        return None
    options = [colors[f"color{n}"] for n in range(1, 15) if f"color{n}" in colors]
    if not options:
        options = [fg]
    dark = luminance(bg) < 0.2
    accent = max(options, key=lambda c: saturation(c) * min(contrast(c, bg), 4.5))
    step = 0
    while contrast(accent, bg) < 3 and step < 12:  # push it away from the background until it reads
        accent = mix(accent, "#FFFFFF" if dark else "#000000", 0.12)
        step += 1
    on = "#FFFFFF" if contrast(accent, "#FFFFFF") >= contrast(accent, "#101010") else "#101010"
    return {"bg": bg, "fg": fg, "accent": accent, "onAccent": on, "dark": dark}


# ── CSS ────────────────────────────────────────────────────────────────────
ACCENT_RULES = """
.visualizer, .waveform { color: @accent_color; }
.lyrics.synced .lyric-line.current { color: @accent_color; }
"""


def mono_css():
    return """
@define-color accent_color @window_fg_color;
@define-color accent_bg_color @window_fg_color;
@define-color accent_fg_color @window_bg_color;
:root { --accent-color: var(--window-fg-color); --accent-bg-color: var(--window-fg-color);
        --accent-fg-color: var(--window-bg-color); }
"""


def palette_css(palette, dark):
    bg, fg, accent, on = palette["bg"], palette["fg"], palette["accent"], palette["onAccent"]
    lift = fg if dark else "#FFFFFF"  # dark surfaces rise toward the text color, light ones toward white
    colors = {
        "window_bg_color": bg, "window_fg_color": fg,
        "view_bg_color": mix(bg, lift, 0.03 if dark else 0.5), "view_fg_color": fg,
        "headerbar_bg_color": mix(bg, fg, 0.04), "headerbar_fg_color": fg,
        "headerbar_backdrop_color": bg,
        "sidebar_bg_color": mix(bg, fg, 0.03), "sidebar_fg_color": fg, "sidebar_backdrop_color": bg,
        "secondary_sidebar_bg_color": mix(bg, fg, 0.02), "secondary_sidebar_fg_color": fg,
        "card_bg_color": mix(bg, lift, 0.06 if dark else 0.6), "card_fg_color": fg,
        "thumbnail_bg_color": mix(bg, lift, 0.06 if dark else 0.6), "thumbnail_fg_color": fg,
        "dialog_bg_color": mix(bg, lift, 0.07 if dark else 0.7), "dialog_fg_color": fg,
        "popover_bg_color": mix(bg, lift, 0.08 if dark else 0.75), "popover_fg_color": fg,
        "accent_color": accent, "accent_bg_color": accent, "accent_fg_color": on,
    }
    named = "\n".join(f"@define-color {name} {value};" for name, value in colors.items())
    variables = " ".join(f"--{name.replace('_', '-')}: {value};" for name, value in colors.items())
    return f"{named}\n:root {{ {variables} }}\n{ACCENT_RULES}"


class ThemeManager:
    """Owns the theme's CSS provider; follows light/dark changes and the wallust file."""

    def __init__(self, data_dir, display=None):
        self.themes = load(Path(data_dir) / "themes" / "themes.json")
        self.provider = Gtk.CssProvider()
        Gtk.StyleContext.add_provider_for_display(display or Gdk.Display.get_default(), self.provider,
                                                  Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION + 1)
        self.manager = Adw.StyleManager.get_default()
        self.manager.connect("notify::dark", lambda *_: self.apply())
        self.store = None
        self.current = MONO
        self.scheme = "auto"
        self.monitor = None
        self.apply()

    def attach(self, store):
        """Read the saved choice once the database is open."""
        self.store = store
        self.current = store.setting("music.theme", MONO)
        self.scheme = store.setting("music.color_scheme", "auto")
        self.apply()

    def choices(self):
        """[(id, name)] for the preferences; the wallpaper theme only when wallust is set up."""
        items = [(theme["id"], theme["name"]) for theme in self.themes]
        if WALLUST_FILE.is_file() or self.current == WALLUST:
            items.append((WALLUST, "Papel de parede (wallust)"))
        return items

    def set_theme(self, theme_id):
        self.current = theme_id
        if self.store is not None:
            self.store.set_setting("music.theme", theme_id)
        self.apply()

    def set_scheme(self, scheme):
        self.scheme = scheme
        if self.store is not None:
            self.store.set_setting("music.color_scheme", scheme)
        self.apply()

    def toggle_dark(self):
        self.set_scheme("light" if self.manager.get_dark() else "dark")

    def apply(self):
        palette = wallust_palette() if self.current == WALLUST else None
        self._watch_wallust(self.current == WALLUST)
        if palette is not None:
            # The wallpaper decides light or dark; libadwaita's own shading has to agree with it.
            wanted = Adw.ColorScheme.FORCE_DARK if palette["dark"] else Adw.ColorScheme.FORCE_LIGHT
        else:
            wanted = dict((key, value) for key, _name, value in SCHEMES).get(self.scheme, Adw.ColorScheme.DEFAULT)
        if self.manager.get_color_scheme() != wanted:
            # If this flips light/dark, notify::dark runs apply() again; if it doesn't (the system was
            # already dark), nothing else will, so the colors are applied right here in both cases.
            self.manager.set_color_scheme(wanted)
        dark = self.manager.get_dark()
        theme = next((t for t in self.themes if t["id"] == self.current), None)
        if palette is not None:
            css = palette_css(palette, palette["dark"])
        elif theme is None or theme["id"] == MONO:
            css = mono_css()
        else:
            css = palette_css(theme["dark" if dark else "light"], dark)
        self.provider.load_from_string(css)

    def _watch_wallust(self, on):
        if on and self.monitor is None and WALLUST_FILE.parent.is_dir():
            self.monitor = Gio.File.new_for_path(str(WALLUST_FILE)).monitor_file(Gio.FileMonitorFlags.NONE, None)
            self.monitor.connect("changed", lambda _m, _f, _o, event: event in (
                Gio.FileMonitorEvent.CHANGES_DONE_HINT, Gio.FileMonitorEvent.CREATED) and self.apply())
        elif not on and self.monitor is not None:
            self.monitor.cancel()
            self.monitor = None
