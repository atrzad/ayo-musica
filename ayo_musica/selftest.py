"""Proves a packaged build works: AYO_SELFTEST=<log file> opens the app, checks the audio elements,
closes it after a few seconds and writes what failed (or "OK")."""
from pathlib import Path
import sys
import traceback

from gi.repository import GLib

# playbin + our filters + the decoders for the formats people actually have.
AUDIO_ELEMENTS = ("playbin", "audioconvert", "audioresample", "volume", "equalizer-10bands", "scaletempo",
                  "spectrum", "level", "rganalysis", "rglimiter", "id3demux", "mpegaudioparse", "flacparse",
                  "oggdemux", "vorbisdec", "opusdec", "wavparse", "qtdemux", "autoaudiosink")
DECODERS = (("mpg123audiodec", "avdec_mp3"), ("flacdec", "avdec_flac"), ("avdec_aac", "faad", "fdkaacdec"))


def missing_audio():
    import gi
    gi.require_version("Gst", "1.0")
    from gi.repository import Gst
    Gst.init(None)
    missing = [name for name in AUDIO_ELEMENTS if Gst.ElementFactory.find(name) is None]
    missing += ["/".join(options) for options in DECODERS
                if not any(Gst.ElementFactory.find(name) for name in options)]
    return [f"Elemento de áudio ausente: {name}" for name in missing]


def run(app, log):
    problems = []

    def hook(kind, value, trace):
        problems.append("".join(traceback.format_exception(kind, value, trace)))
    sys.excepthook = hook

    def check():
        try:
            if app.window is None or not app.window.get_visible():
                problems.append("A janela não abriu")
            problems.extend(missing_audio())
        except Exception:  # noqa: BLE001 - everything goes to the log
            problems.append(traceback.format_exc())
        if app.window is not None:
            app.window.quit_app()
        app.quit()
        return GLib.SOURCE_REMOVE

    app.connect_after("activate", lambda *_: GLib.timeout_add_seconds(4, check))
    status = app.run([sys.argv[0]])
    ok = status == 0 and not problems
    Path(log).write_text("OK\n" if ok else "\n".join(problems or [f"Saiu com código {status}"]) + "\n",
                         encoding="utf-8")
    return 0 if ok else 1
