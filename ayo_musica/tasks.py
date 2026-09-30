"""Run blocking work off the GTK main loop, and small external commands."""
from concurrent.futures import ThreadPoolExecutor
import os
import subprocess

from gi.repository import GLib

POOL = ThreadPoolExecutor(max_workers=3, thread_name_prefix="ayo")


def background(work, done, executor=POOL):
    future = executor.submit(work)

    def finish(task):
        try:
            value, error = task.result(), None
        except Exception as exc:
            value, error = None, str(exc)

        def deliver():
            done(value, error)
            return GLib.SOURCE_REMOVE
        GLib.idle_add(deliver)
    future.add_done_callback(finish)


def command(argv, timeout=12):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=timeout,
                                env={**os.environ, "LC_ALL": "C"}, check=False)
    except FileNotFoundError as exc:
        raise RuntimeError(f"Comando necessário não encontrado: {argv[0]}") from exc
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or f"{argv[0]} terminou com erro {result.returncode}")
    return result.stdout
