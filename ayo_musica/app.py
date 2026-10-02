"""The application and its window: header bar, toasts and the music page."""
import os
# A forced GTK3 theme suppresses libadwaita's layout rules (notably row padding).
# Scope the native style to this process, before GTK is initialized.
os.environ.pop("GTK_THEME", None)

from pathlib import Path

import gi
gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gdk, Gio, GLib, Gtk

from . import APP_ID
from .store import Store

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"


class Window(Adw.ApplicationWindow):
    def __init__(self, app, store=None):
        super().__init__(application=app, title="Ayo Música", default_width=1200, default_height=780)
        self.set_size_request(360, 480)
        self.store = store or Store()
        self.closed = False
        self.force_quit = False
        self.overlay = Adw.ToastOverlay()
        self.set_content(self.overlay)
        outer = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        self.overlay.set_child(outer)
        self.header = Adw.HeaderBar()
        self.header.set_title_widget(Adw.WindowTitle(title="Ayo Música"))
        theme = Gtk.Button(icon_name="display-brightness-symbolic", tooltip_text="Alternar tema claro/escuro")
        theme.connect("clicked", lambda _b: self.toggle_theme())
        self.header.pack_end(theme)
        outer.append(self.header)
        from .ui.window import MusicPage
        self.page = MusicPage(self)
        self.page.set_vexpand(True)
        outer.append(self.page)
        self.connect("close-request", self._closing)
        if self.store.imported:
            self.notify("Sua biblioteca, playlists, estatísticas e backups de tags vieram da instalação anterior.")

    def toggle_theme(self):
        themes = self.get_application().themes
        if themes.current == "wallust":
            self.notify("O tema Papel de parede segue o claro ou escuro do wallust.")
            return
        themes.toggle_dark()

    def notify(self, message):
        if not self.closed:
            self.overlay.add_toast(Adw.Toast(title=str(message), timeout=7))

    def quit_app(self):
        """Close for real, even while music is playing."""
        self.force_quit = True
        self.close()

    def _closing(self, *_):
        if self.closed:
            return False
        if not self.force_quit and self.page.keep_running():
            self.set_visible(False)  # the music keeps playing; launching the app again shows the window
            self.page.on_hidden()
            return True
        self.closed = True
        self.page.close()
        self.store.close()
        return False


class Application(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE)
        self.window = None

    def do_startup(self):
        Adw.Application.do_startup(self)
        display = Gdk.Display.get_default()
        provider = Gtk.CssProvider()
        provider.load_from_path(str(DATA / "style.css"))
        Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        from .ui.themes import ThemeManager
        self.themes = ThemeManager(DATA, display)  # colors on top of the base style; light/dark follows the system
        # The app icon ships with the code (for running from the source tree).
        Gtk.IconTheme.get_for_display(display).add_search_path(str(DATA / "icons"))
        Gtk.Window.set_default_icon_name(APP_ID)
        quit_action = Gio.SimpleAction.new("quit", None)
        quit_action.connect("activate", lambda *_: self.window.quit_app() if self.window else self.quit())
        self.add_action(quit_action)
        self.set_accels_for_action("app.quit", ["<Primary>q"])

    def do_activate(self):
        if self.window is None or self.window.closed:
            self.window = Window(self)
            self.themes.attach(self.window.store)
        self.window.present()
        self.window.page.on_show()

    def do_command_line(self, command_line):
        self.activate()
        files = [command_line.create_file_for_arg(arg).get_path() or arg for arg in file_arguments(command_line)]
        if files:
            self.window.page.open_files(files)
        return 0


def file_arguments(command_line):
    """Positional arguments (files or URIs) given on the command line."""
    return [arg for arg in command_line.get_arguments()[1:] if not arg.startswith("-")]
