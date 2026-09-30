#!/usr/bin/env python3
"""Verifica as dependências do Ayo Música sem alterar o sistema."""
import importlib.util
import shutil
import subprocess
import sys

REQUIRED_MODULES = {"gi": "PyGObject"}
OPTIONAL_MODULES = {"mutagen": "python-mutagen / tags, capas e letras das músicas"}
# Elementos GStreamer usados pelo player (gst-plugins-base e gst-plugins-good).
GST_ELEMENTS = ("playbin", "equalizer-10bands", "rgvolume", "rglimiter", "scaletempo", "level", "spectrum")
OPTIONAL_COMMANDS = (("cava", "visualizador que reage a todo o som do computador", "cava"),
                     ("songrec", "reconhecer músicas pelo som", "songrec"),
                     ("whisper-cli", "sincronizar letras pela voz", "whisper-cpp"))


def main():
    failed = False
    print("Ayo Música — verificação")
    for module, purpose in REQUIRED_MODULES.items():
        found = importlib.util.find_spec(module) is not None
        print(f"{'OK' if found else 'FALTA':5} Python {module:12} {purpose}")
        failed |= not found
    for module, purpose in OPTIONAL_MODULES.items():
        found = importlib.util.find_spec(module) is not None
        print(f"{'OK' if found else 'AVISO':5} Python {module:12} {purpose}")
    if shutil.which("gst-inspect-1.0"):
        missing = [name for name in GST_ELEMENTS
                   if subprocess.run(["gst-inspect-1.0", "--exists", name], check=False).returncode != 0]
        print(f"{'OK' if not missing else 'FALTA':5} {'GStreamer':18} "
              + ("efeitos de áudio disponíveis" if not missing else "faltam: " + ", ".join(missing)))
        failed |= bool(missing)
    for command, purpose, package in OPTIONAL_COMMANDS:
        found = shutil.which(command) is not None
        print(f"{'OK' if found else 'AVISO':5} {command:18} {purpose} (sudo pacman -S {package})")
    families = subprocess.run(["fc-list", ":", "family"], capture_output=True, text=True,
                              check=False).stdout if shutil.which("fc-list") else ""
    for family, package, purpose in (("Noto Sans CJK", "noto-fonts-cjk", "japonês, chinês e coreano"),
                                     ("Noto Color Emoji", "noto-fonts-emoji", "emojis"),
                                     ("Noto Sans Arabic", "noto-fonts-extra", "árabe, hebraico, tailandês...")):
        found = family in families
        print(f"{'OK' if found else 'AVISO':5} {package:18} fonte para {purpose}")
    print("Dependências essenciais encontradas." if not failed else "Instale os itens marcados como FALTA.")
    return int(failed)


if __name__ == "__main__":
    sys.exit(main())
