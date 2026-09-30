#!/usr/bin/env python3
"""Exercise the real Ayo Música window with an isolated database and music folder; never makes sound."""
import argparse
import os
from pathlib import Path
import sys
import subprocess
import tempfile
import time
import traceback
import wave

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))

parser = argparse.ArgumentParser()
parser.add_argument("--snapshots", type=Path)
args = parser.parse_args()
if args.snapshots:
    args.snapshots.mkdir(parents=True, exist_ok=True)
temp = tempfile.TemporaryDirectory(prefix="ayo-smoke-")
# Keep the real session/system buses available, but isolate all app data and never make sound.
os.environ["XDG_DATA_HOME"] = temp.name
os.environ["XDG_CACHE_HOME"] = str(Path(temp.name) / "cache")
os.environ["AYO_MUSIC_SINK"] = "fakesink"
os.environ["AYO_NO_MPRIS"] = "1"  # don't show the test player in the real desktop's media widgets
os.environ["AYO_NO_IMPORT"] = "1"  # never copy the real library into the test database

from ayo_musica.app import Application
from ayo_musica.store import Store
from gi.repository import Adw, Gdk, Gio, GLib, Graphene, Gsk, Gtk

failures = []
music_stage = 0
music_deadline = None
music_fixture = None

if True:
    original = Path(temp.name) / "Coleção inicial"
    replacement = Path(temp.name) / "Minha música"
    first = original / "Álbum" / "Faixa inicial.WAV"
    second = replacement / "Outro álbum" / "Faixa da nova pasta.wav"
    for track in (first, second):
        track.parent.mkdir(parents=True, exist_ok=True)
        with wave.open(str(track), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(8000)
            output.writeframes(b"\0\0" * 800)
    cache = Store()
    cache.sync_music_folder(original, [])
    cache.set_setting("music.identify_new", False)  # never search online during the smoke run
    cache.set_setting("music.lyrics_online", False)
    cache.set_setting("music.lyrics_voice", False)
    cache.close()
    music_fixture = original, replacement, first, second


def exception_hook(kind, value, tb):
    failures.append(str(value))
    traceback.print_exception(kind, value, tb)


sys.excepthook = exception_hook
app = Application()
application_id = app.get_application_id()
app.set_application_id("io.github.atrzad.AyoMusica.Smoke")
app.set_flags(Gio.ApplicationFlags.HANDLES_COMMAND_LINE | Gio.ApplicationFlags.NON_UNIQUE)
done = False


def screenshot(window, name):
    if not args.snapshots:
        return
    snapshot = Gtk.Snapshot.new()
    background = Gdk.RGBA()
    background.parse("#222226" if Adw.StyleManager.get_default().get_dark() else "#fafafb")
    snapshot.append_color(background, Graphene.Rect().init(0, 0, window.get_width(), window.get_height()))
    window.snapshot_child(window.get_child(), snapshot)
    node = snapshot.to_node()
    if node:
        renderer = Gsk.CairoRenderer.new()
        renderer.realize(None)
        try:
            texture = renderer.render_texture(node, None)
            texture.save_to_png(str(args.snapshots / f"{name}.png"))
        finally:
            renderer.unrealize()


def inspect():
    global music_stage, music_deadline
    window = app.window
    key = "music"
    page = window.page
    try:
        assert application_id == "io.github.atrzad.AyoMusica", application_id
        assert window.get_title() == "Ayo Música"
        if True:
            if music_deadline is None:
                music_deadline = time.monotonic() + 8
            if page.scanning:
                assert time.monotonic() < music_deadline, "A busca da pasta não terminou"
                GLib.timeout_add(100, inspect)
                return GLib.SOURCE_REMOVE
            original, replacement, first, second = music_fixture
            if music_stage < 2:
                if music_stage == 0:
                    assert [page.library.tracks.get_item(n).path for n in range(page.library.tracks.get_n_items())] \
                        == [str(first)], "A pasta salva deve ser lida automaticamente ao abrir"
                    assert page.library.get(str(first)).title == "Faixa inicial"
                    assert page.library.albums.get_n_items() == 1
                    assert page.status_detail.get_tooltip_text() == str(original)
                    from unittest.mock import patch

                    class SelectedFolder:
                        def __init__(self, **_kwargs):
                            pass

                        def set_initial_folder(self, folder):
                            assert folder.get_path() == str(original)

                        def select_folder(self, parent, _cancel, callback):
                            assert parent is window
                            callback(self, None)

                        def select_folder_finish(self, _result):
                            return Gio.File.new_for_path(str(replacement))

                    music_stage = 1
                    with patch("ayo_musica.ui.window.Gtk.FileDialog", SelectedFolder):
                        page.choose_folder()
                    GLib.timeout_add(100, inspect)
                    return GLib.SOURCE_REMOVE
                paths = [page.library.tracks.get_item(n).path for n in range(page.library.tracks.get_n_items())]
                assert paths == [str(second)], "Trocar de pasta deve atualizar a biblioteca"
                assert window.store.music_folder() == str(replacement)
                assert page.rescan_button.get_sensitive()
                assert first.is_file(), "A troca de pasta não pode apagar arquivos"
                page.play_all()
                assert page.current_path == str(second), "Tocar tudo deve começar a reprodução"
                assert page.bar.title.get_text() == "Faixa da nova pasta"
                for view in ("queue", "songs", "albums", "artists", "genres", "folders"):
                    page.show_view(view)
                    assert page.stack.get_visible_child_name() == view
                second.with_name(second.stem + ".lrc").write_text(
                    "[ar:Teste]\n[00:00.00]Primeira linha da letra\n[00:00.50]Segunda linha da letra\n"
                    "[00:30.00]Terceira linha\n", encoding="utf-8")
                page.start(str(second), play=False)  # the test track is 1 s long: keep it loaded, paused
                page.expand(fullscreen=False)
                assert page.is_expanded() and page.bar.expand.get_icon_name() == "go-down-symbolic"
                assert window.header.get_visible(), "Fora da tela cheia a barra de título continua"
                context, until = GLib.MainContext.default(), time.monotonic() + 5
                while time.monotonic() < until and not (page.expanded.lyrics_view.labels
                                                        and page.expanded.lyrics_view.current >= 0):
                    context.iteration(False)
                lyrics_view = page.expanded.lyrics_view
                assert [label.get_text() for label in lyrics_view.labels] == [
                    "Primeira linha da letra", "Segunda linha da letra", "Terceira linha"], "Letra .lrc não carregou"
                assert lyrics_view.current in (0, 1), lyrics_view.current
                assert lyrics_view.labels[lyrics_view.current].has_css_class("current")
                assert page.expanded.status.get_text() == "Letra: arquivo ao lado da música"
                page.expanded.manager.shift(500)
                assert page.music.lyrics_row(str(second))["offset_ms"] == 500, "O ajuste da letra não foi salvo"
                page.expanded.manager.shift(-500)
                until = time.monotonic() + 0.6
                while time.monotonic() < until:
                    context.iteration(False)
                screenshot(window, "music-expanded")
                page.collapse()
                page.expand(fullscreen=True)
                until = time.monotonic() + 3
                while time.monotonic() < until and not window.is_fullscreen():
                    context.iteration(False)
                until = time.monotonic() + 1
                while time.monotonic() < until:
                    context.iteration(False)
                if window.is_fullscreen():  # some sessions (e.g. without a compositor) refuse full screen
                    assert not window.header.get_visible(), "Na tela cheia a barra de título some"
                    screenshot(window, "music-expanded-full")
                page.collapse()
                until = time.monotonic() + 3
                while time.monotonic() < until and window.is_fullscreen():
                    context.iteration(False)
                assert not window.is_fullscreen(), "Recolher sai da tela cheia"
                assert not page.is_expanded() and window.header.get_visible()
                page.show_view("songs")
                page.toggle_expanded()
                assert page.is_expanded()
                page.show_view("queue")
                assert not page.is_expanded(), "Abrir outra tela recolhe o player expandido"
                page.open_album_of(page.library.get(str(second)))
                for text, expected in (("NOVA pasta", 1), ("não existe nada assim", 0), ("", 1)):
                    page.search_entry.set_text(text)
                    page.on_search(page.search_entry)  # the entry itself waits a moment before searching
                    assert page.songs.table.count() == expected, (text, page.songs.table.count())
                page.enqueue([str(first), str(second)])
                assert page.queue.items() == [str(second), str(first), str(second)]
                page.move_in_queue(2, 1)
                assert page.queue.items() == [str(second), str(second), str(first)]
                page.show_view("queue")
                page.set_sleep(15)
                assert "15 min" in page.sleep_text()
                page.set_sleep(None)
                page.set_rate(1.25)
                assert page.player.rate == 1.25 and window.store.setting("music.rate") == 1.25
                page.set_rate(1.0)
                playlist = page.music.create_playlist("Teste", [str(second)])
                page.refresh_playlists()
                page.show_playlist(playlist)
                assert page.stack.get_visible_child_name() == "playlist"
                assert page.playlist_view.table.count() == 1
                page.add_to_playlist(playlist, [str(first)])
                page.add_to_playlist(playlist, [str(first)])  # duplicates are skipped
                assert page.music.playlist_paths(playlist) == [str(second), str(first)]
                page.playlist_view.reorder([1], 0)
                assert page.music.playlist_paths(playlist) == [str(first), str(second)]
                assert page.playlist_view.table.count() == 2
                assert page.rows[f"playlist:{playlist}"].label.get_text() == "Teste"
                page.playlist_view.table.play_from(1)
                assert page.current_path == str(second)
                page.remove_from_playlist(playlist, [0])
                assert page.music.playlist_paths(playlist) == [str(second)]
                assert page.keep_running(), "Fechar a janela tocando deve manter a música"
                window.close()
                assert not window.get_visible() and not window.closed
                window.present()
                from ayo_musica.identify import writer
                if writer.available():  # identification writes tags, which needs python-mutagen

                    class FakeIdentifier:
                        def identify(self, info):
                            return {"path": info["path"], "mtime_ns": info.get("mtime_ns"), "status": "auto",
                                    "confidence": 97, "source": "deezer", "reasons": ["teste"], "candidates": [],
                                    "changes": {"artist": "Artista Identificado", "album": "Álbum Identificado"},
                                    "isrc": "", "proposed": {}, "current": {"title": "Faixa da nova pasta", "cover": ""}}
                    page.organizer.identifier = lambda: FakeIdentifier()
                    page.show_view("organize")
                    page.organizer.start([str(second)], confirm=False)
                    music_stage, music_deadline = 2, time.monotonic() + 15
                    GLib.timeout_add(100, inspect)
                    return GLib.SOURCE_REMOVE
            if music_stage == 2:
                track = page.library.get(str(second))
                if track is None or track.artist != "Artista Identificado":
                    assert time.monotonic() < music_deadline, "A identificação automática não gravou a tag"
                    GLib.timeout_add(100, inspect)
                    return GLib.SOURCE_REMOVE
                assert page.music.identify_result(str(second))["status"] == "applied"
                backups = page.music.backups()
                assert [b["path"] for b in backups] == [str(second)], backups
                assert page.organizer.view.lists["applied"][0].get_first_child() is not None, "Lista de aplicadas vazia"
                page.organizer.undo(backups)
                music_stage, music_deadline = 3, time.monotonic() + 15
                GLib.timeout_add(100, inspect)
                return GLib.SOURCE_REMOVE
            if music_stage == 3:
                track = page.library.get(str(second))
                if track is None or track.artist == "Artista Identificado":
                    assert time.monotonic() < music_deadline, "Desfazer não restaurou a tag"
                    GLib.timeout_add(100, inspect)
                    return GLib.SOURCE_REMOVE
                assert page.music.backups() == [], "O backup desfeito não pode continuar pendente"
                assert page.music.identify_result(str(second))["status"] == "rejected"
                print("PASS identificação: gravou a tag, listou em Aplicadas e desfez", flush=True)
        print(f"PASS UI: {key} ({application_id})", flush=True)
    except Exception:
        exception_hook(*sys.exc_info())
    def capture_and_continue():
        screenshot(window, key)
        GLib.idle_add(finish)
        return GLib.SOURCE_REMOVE
    window.queue_draw()
    GLib.timeout_add(300, capture_and_continue)
    return GLib.SOURCE_REMOVE


def finish():
    app.window.quit_app()
    app.quit()
    return GLib.SOURCE_REMOVE


def activated(_app):
    global done
    if done:
        return
    done = True
    Gtk.Settings.get_default().set_property("gtk-enable-animations", False)
    GLib.timeout_add(1800, inspect)


app.connect_after("activate", activated)
app.run(["ayo-smoke"])
temp.cleanup()
print(f"UI smoke: {len(failures)} failure(s)")
sys.exit(bool(failures))
