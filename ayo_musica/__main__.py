import argparse
import os
import sys

from . import __version__


def main():
    parser = argparse.ArgumentParser(prog="ayo-musica", description="Ayo Música — player de música local")
    parser.add_argument("files", nargs="*", help="arquivos de áudio ou pastas para tocar")
    parser.add_argument("--version", action="version", version=__version__)
    parser.parse_args()
    try:
        from .app import Application
    except (ImportError, ValueError) as exc:
        print(f"Dependência gráfica indisponível: {exc}\nConsulte as dependências no README.md.", file=sys.stderr)
        return 1
    if os.environ.get("AYO_SELFTEST"):
        from .selftest import run
        return run(Application(), os.environ["AYO_SELFTEST"])
    return Application().run(sys.argv)


if __name__ == "__main__":
    sys.exit(main())
