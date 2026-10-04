# Licenças do app Android

O app Android (esta pasta, `android/`) é distribuído sob a **GNU General Public License v3.0** (veja `LICENSE`),
porque inclui o algoritmo de impressão digital do Shazam portado do SongRec, que é GPL-3.0. O app de desktop (o resto
do repositório) continua sob a licença MIT do arquivo `LICENSE` na raiz.

Componentes de terceiros:

| Componente | Uso | Licença |
| --- | --- | --- |
| [SongRec](https://github.com/marin-m/SongRec) (marin-m) | Algoritmo da assinatura do Shazam, portado para Kotlin em `analyzer/Shazam.kt` | GPL-3.0 |
| [Chromaprint](https://github.com/acoustid/chromaprint) | Impressão digital para o AcoustID (compilado do lançamento oficial pelo CMake) | LGPL-2.1 |
| [KissFFT](https://github.com/mborgerding/kissfft) | FFT usada pelo Chromaprint | BSD-3-Clause |
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) | Sincronização e transcrição de letras pela voz | MIT |
| [Pixelify Sans](https://github.com/eifetx/Pixelify-Sans) | Fonte do tema Pixel (`licenses/PixelifySans-OFL.txt`) | OFL-1.1 |

Serviços consultados pela internet: Deezer, Apple Music (iTunes Search API), MusicBrainz e Cover Art Archive,
AcoustID (com a chave da própria pessoa), Shazam (sem API pública: só a impressão digital do som é enviada),
LRCLIB, NetEase e lyrics.ovh.
