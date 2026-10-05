#!/usr/bin/env python3
"""End-to-end check of the account features on the real desktop window, against a running sync server:
sync, the cloud library, streaming a cloud song and this computer as a device. Isolated data; never makes sound.

    AYO_CLOUD_SERVER=http://127.0.0.1:3600 AYO_CLOUD_TOKEN=<admin.js token> python3 scripts/cloud_check.py --snapshots DIR
"""
import argparse
import json
import os
from pathlib import Path
import sys
import tempfile
import time
import urllib.request
import wave

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
parser = argparse.ArgumentParser()
parser.add_argument("--snapshots", type=Path)
args = parser.parse_args()
SERVER, TOKEN = os.environ["AYO_CLOUD_SERVER"], os.environ["AYO_CLOUD_TOKEN"]
temp = tempfile.TemporaryDirectory(prefix="ayo-cloud-")
os.environ.update(XDG_DATA_HOME=temp.name, XDG_CACHE_HOME=str(Path(temp.name) / "cache"), AYO_MUSIC_SINK="fakesink",
                  AYO_NO_MPRIS="1", AYO_NO_IMPORT="1")

from ayo_musica.app import Application  # noqa: E402
from ayo_musica.store import Store  # noqa: E402
from gi.repository import Gio, GLib, Gsk, Gtk  # noqa: E402

folder = Path(temp.name) / "Música"
track = folder / "Teste - Faixa local.wav"
track.parent.mkdir(parents=True)
with wave.open(str(track), "wb") as output:
    output.setnchannels(1), output.setsampwidth(2), output.setframerate(8000), output.writeframes(b"\0\0" * 8000)
store = Store()
store.sync_music_folder(folder, [])
for key, value in (("identify_new", False), ("lyrics_online", False), ("lyrics_voice", False), ("cloud_server", SERVER),
                   ("cloud_token", TOKEN), ("cloud_email", "teste"), ("cloud_name", "Teste")):
    store.set_setting(f"music.{key}", value)
store.close()

app = Application()
app.set_application_id("io.github.atrzad.AyoMusica.CloudCheck")
app.set_flags(Gio.ApplicationFlags.HANDLES_COMMAND_LINE | Gio.ApplicationFlags.NON_UNIQUE)
report = {}
failures = []
started = time.time()


def server(path):
    request = urllib.request.Request(SERVER + path, headers={"Authorization": f"Bearer {TOKEN}"})
    with urllib.request.urlopen(request, timeout=10) as response:
        return json.loads(response.read())


def screenshot(name):
    if not args.snapshots:
        return
    args.snapshots.mkdir(parents=True, exist_ok=True)
    window = app.window
    window.queue_draw()
    deadline = time.time() + 0.4
    while time.time() < deadline:  # let it draw before taking the picture
        GLib.MainContext.default().iteration(False)
    paintable = Gtk.WidgetPaintable.new(window)
    snapshot = Gtk.Snapshot.new()
    paintable.snapshot(snapshot, window.get_width(), window.get_height())
    node = snapshot.to_node()
    if node:
        renderer = Gsk.CairoRenderer.new()
        renderer.realize(None)
        renderer.render_texture(node, None).save_to_png(str(args.snapshots / f"{name}.png"))
        renderer.unrealize()


stage = 0


def step():
    global stage
    page = app.window.page
    cloud = page.cloud
    try:
        if stage == 0:  # wait for the first sync
            if (cloud.status["last"] and time.time() - cloud.status["last"] > 25) or time.time() - started > 120:
                report["sync"] = dict(cloud.status)
                report["cloud_tracks"] = len(cloud.library.tracks)
                report["library_all"] = page.library.tracks.get_n_items()
                report["playlists"] = [p["name"] for p in page.music.playlists()]
                screenshot("1-tudo")
                cloud.set_source("cloud")
                stage = 1
        elif stage == 1:
            report["library_cloud"] = page.library.tracks.get_n_items()
            screenshot("2-nuvem")
            url = next(t for t in (page.library.tracks.get_item(n) for n in range(page.library.tracks.get_n_items()))
                       if cloud.account.owns(t.path)).path
            report["playing"] = url
            page.play_paths([url], 0)
            stage, report["play_started"] = 2, time.time()
        elif stage == 2 and time.time() - report["play_started"] > 6:
            position, duration = page.player.position()
            report["position"], report["duration"] = round(position, 1), round(duration, 1)
            screenshot("3-tocando")
            cloud.publish_soon(0)
            stage, report["published"] = 3, time.time()
        elif stage == 3 and time.time() - report["published"] > 3:
            devices = server("/api/player/devices")["devices"]
            me = next((d for d in devices if d["device"] == cloud.account.device), None)
            report["device"] = me and {"name": me["name"], "online": me["online"],
                                       "title": ((me.get("state") or {}).get("item") or {}).get("title")}
            app.window.quit_app()
            app.quit()
            return False
    except Exception as error:  # noqa: BLE001
        failures.append(repr(error))
        app.window.quit_app()
        app.quit()
        return False
    return True


app.connect_after("activate", lambda _a: GLib.timeout_add(500, step))
app.run(["ayo-cloud-check"])
print(json.dumps({"report": report, "failures": failures}, ensure_ascii=False, indent=1))
