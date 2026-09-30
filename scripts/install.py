#!/usr/bin/env python3
"""Install Ayo Música: for this user under ~/.local (make install), or into a prefix for packages.

    python3 scripts/install.py                     # ~/.local
    python3 scripts/install.py --uninstall
    python3 scripts/install.py --prefix /app       # Flatpak (inside flatpak-builder)
    python3 scripts/install.py --prefix /usr --destdir "$pkgdir"
"""
import argparse
import compileall
import os
from pathlib import Path
import shlex
import shutil
import subprocess

ROOT = Path(__file__).resolve().parent.parent
APP_ID = "io.github.atrzad.AyoMusica"
COMMAND = "ayo-musica"
HOME = Path.home() / ".local"


class Layout:
    """Where each part goes. Paths are the run-time ones; `staged` adds DESTDIR for packaging."""

    def __init__(self, prefix=None, destdir=""):
        self.user = prefix is None
        self.prefix = HOME if self.user else Path(prefix)
        self.destdir = destdir
        self.app = HOME / "share/ayo-musica/app" if self.user else self.prefix / "lib/ayo-musica"
        self.bin = self.prefix / "bin"
        share = Path(os.environ.get("XDG_DATA_HOME", HOME / "share")) if self.user else self.prefix / "share"
        self.desktop = share / "applications" / f"{APP_ID}.desktop"
        self.icon = share / "icons/hicolor/scalable/apps" / f"{APP_ID}.svg"
        self.metainfo = share / "metainfo" / f"{APP_ID}.metainfo.xml"

    def staged(self, path):
        return Path(self.destdir + str(path)) if self.destdir else path


def desktop_quote(value):
    return '"' + str(value).replace('\\', '\\\\').replace('"', '\\"').replace('`', '\\`').replace('$', '\\$').replace('%', '%%') + '"'


def write(path, text, mode=0o644):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    path.chmod(mode)


def install(layout):
    app = layout.staged(layout.app)
    if app.exists():
        shutil.rmtree(app)
    for folder in ("ayo_musica", "data"):
        shutil.copytree(ROOT / folder, app / folder, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
    if not layout.user:
        compileall.compile_dir(app / "ayo_musica", quiet=1, ddir=str(layout.app / "ayo_musica"))
    python = "/usr/bin/python3" if layout.user else "python3"
    write(layout.staged(layout.bin / COMMAND),
          f"#!/bin/sh\nPYTHONPATH={shlex.quote(str(layout.app))}${{PYTHONPATH:+:$PYTHONPATH}} "
          f'exec {python} -m ayo_musica "$@"\n', 0o755)
    desktop = (ROOT / f"data/{APP_ID}.desktop").read_text(encoding="utf-8")
    if layout.user:  # menus may not have ~/.local/bin in PATH
        desktop = desktop.replace(f"Exec={COMMAND} ", f"Exec={desktop_quote(layout.bin / COMMAND)} ")
    write(layout.staged(layout.desktop), desktop)
    for source, target in ((ROOT / f"data/icons/hicolor/scalable/apps/{APP_ID}.svg", layout.icon),
                           (ROOT / f"data/{APP_ID}.metainfo.xml", layout.metainfo)):
        target = layout.staged(target)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, target)
    if layout.user:
        for tool in (["update-desktop-database", str(layout.desktop.parent)],
                     ["gtk-update-icon-cache", "-qtf", str(layout.icon.parents[2])]):
            if shutil.which(tool[0]):
                subprocess.run(tool, check=False)
        print(f"Instalado em {layout.app}. Abra o Ayo Música pelo menu de aplicativos.")


def uninstall(layout):
    for path in (layout.desktop, layout.icon, layout.metainfo, layout.bin / COMMAND):
        path.unlink(missing_ok=True)
    if layout.app.exists():
        shutil.rmtree(layout.app)
    print("Ayo Música removido. Sua biblioteca, playlists e estatísticas foram preservadas.")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--uninstall", action="store_true")
    parser.add_argument("--prefix", help="instala para um pacote (ex.: /app ou /usr) em vez de ~/.local")
    parser.add_argument("--destdir", default="", help="raiz temporária do pacote")
    args = parser.parse_args()
    layout = Layout(args.prefix, args.destdir)
    uninstall(layout) if args.uninstall else install(layout)


if __name__ == "__main__":
    main()
