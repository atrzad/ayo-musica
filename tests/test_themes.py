from pathlib import Path
import tempfile
import unittest

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from ayo_musica.ui import themes

DATA = Path(__file__).resolve().parent.parent / "data"


class PaletteTests(unittest.TestCase):
    """The same palettes feed the Android app: every one must stay readable."""

    def test_every_theme_is_readable_in_light_and_dark(self):
        palettes = themes.load(DATA / "themes" / "themes.json")
        self.assertGreaterEqual(len(palettes), 11)
        self.assertEqual(palettes[0]["id"], themes.MONO)
        self.assertEqual(len({p["id"] for p in palettes}), len(palettes))
        for theme in palettes:
            for mode in ("light", "dark"):
                p = theme[mode]
                with self.subTest(theme=theme["id"], mode=mode):
                    self.assertGreaterEqual(themes.contrast(p["fg"], p["bg"]), 7, "texto")
                    self.assertGreaterEqual(themes.contrast(p["accent"], p["onAccent"]), 4.5, "botão")
                    self.assertGreaterEqual(themes.contrast(p["accent"], p["bg"]), 3, "destaque")
                    self.assertEqual(themes.luminance(p["bg"]) < 0.2, mode == "dark")

    def test_css_sets_libadwaita_variables_and_named_colors(self):
        css = themes.palette_css({"bg": "#1C0F12", "fg": "#F2DFE2", "accent": "#E07A8D", "onAccent": "#22090F"}, True)
        self.assertIn("--accent-bg-color: #E07A8D;", css)
        self.assertIn("@define-color window_bg_color #1C0F12;", css)
        self.assertIn(".visualizer, .waveform { color: @accent_color; }", css)
        self.assertIn("var(--window-fg-color)", themes.mono_css())

    def test_mix(self):
        self.assertEqual(themes.mix("#000000", "#FFFFFF", 0.5), "#808080")
        self.assertEqual(themes.mix("#102030", "#102030", 0.7), "#102030")


class WallustTests(unittest.TestCase):
    def test_accent_is_colorful_and_readable(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "colors.css"
            path.write_text("@define-color background #180E0E;\n@define-color foreground #DCD0D0;\n"
                            "@define-color color1 #654041;\n@define-color color4 #856C6E;\n"
                            "@define-color color9 #C0394A;\n@define-color color15 #C4B3B4;\n", encoding="utf-8")
            palette = themes.wallust_palette(path)
        self.assertTrue(palette["dark"])
        self.assertEqual(palette["bg"], "#180E0E")
        self.assertGreaterEqual(themes.contrast(palette["accent"], palette["bg"]), 3)
        self.assertGreaterEqual(themes.contrast(palette["accent"], palette["onAccent"]), 4.5)
        self.assertGreater(themes.saturation(palette["accent"]), 0.4, palette["accent"])

    def test_missing_or_incomplete_file(self):
        self.assertIsNone(themes.wallust_palette("/nao/existe/colors.css"))
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "colors.css"
            path.write_text("@define-color color1 #654041;", encoding="utf-8")
            self.assertIsNone(themes.wallust_palette(path))


if __name__ == "__main__":
    unittest.main()
