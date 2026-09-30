# Ayo Música

Player de música local para Linux, feito em Python, GTK4/libadwaita e GStreamer, em preto e branco seguindo o tema claro ou escuro do sistema. Biblioteca por tags e capas, fila, playlists, som caprichado (nivelamento, equalizador, crossfade, sem intervalos), tela cheia com visualizador e letra sincronizada, identificação de músicas com capa oficial e teclas de mídia (MPRIS).

Também tem versão para **Android**, nativa (Kotlin + Media3), com o mesmo visual.

Veio do [Ayo Desk](https://github.com/atrzad/ayo-desk), onde nasceu como uma das ferramentas da suíte; o histórico até a versão 0.2.0 está lá.

## Instalar

**Flatpak** (qualquer distro):

```sh
make flatpak-install        # gera build/ayo-musica.flatpak e instala para o seu usuário
flatpak run io.github.atrzad.AyoMusica
```

Na primeira vez, `make flatpak` baixa o SDK do GNOME 50 e o `org.flatpak.Builder` do Flathub (`flatpak install --user flathub org.flatpak.Builder org.gnome.Sdk//50`). O pacote pronto também sai no GitHub Actions (artefato **ayo-musica-flatpak**, e anexado às releases `v*`); para instalar: `flatpak install --user ayo-musica.flatpak`.

O Flatpak pode: tocar som, ler e gravar tags na pasta **Músicas** (`xdg-music`; outras pastas passam pelo seletor de arquivos), acessar a internet (letras, identificação e capas), publicar o MPRIS e usar o `cava`, o `songrec`, o `whisper-cpp` e o `pactl` **instalados no sistema** (via `flatpak-spawn --host`). Também lê, só para importar, a biblioteca de uma instalação anterior.

**Android** (8.0 ou mais novo): baixe o `ayo-musica-<versão>-android.apk` do GitHub Actions (artefato **ayo-musica-android**) ou de uma release `v*` e instale no celular (permita "instalar apps desta fonte"). As versões seguintes instalam por cima, mantendo playlists e fila. Para compilar: veja `android/README.md`.

**Direto do código** (Arch):

```sh
sudo pacman -S --needed python python-gobject gtk4 libadwaita gstreamer gst-plugins-base gst-plugins-good gst-libav python-mutagen
make run          # abre sem instalar
make install      # instala em ~/.local (make uninstall remove)
make doctor       # confere dependências e opcionais
```

Opcionais: `cava` (visualizador com todo o som do computador), `songrec` (reconhecer músicas pelo som), `whisper-cpp` (sincronizar letras pela voz), `noto-fonts-cjk`, `noto-fonts-emoji` e `noto-fonts-extra` (títulos em outros alfabetos).

**Vindo do Ayo Desk:** na primeira vez que abre, o Ayo Música copia a biblioteca, as playlists, as estatísticas, os backups de tags e as capas da Música do Ayo Desk (`~/.local/share/ayo-desk`). Nada lá é alterado.

## Usar

1. Abra **Ayo Música** e escolha a pasta da sua coleção (ou use `~/Music` direto na tela inicial).
2. O Ayo busca os arquivos na pasta e nas subpastas, em segundo plano, lê as tags e as capas e guarda tudo no banco local. Nas próximas vezes só relê o que mudou.
3. Enquanto o app está aberto, a pasta é acompanhada: músicas novas, removidas ou editadas aparecem sozinhas (dá para desligar em **Preferências**).

Quando o arquivo não tem tags, o Ayo usa o nome e a pasta: `Album - Gêmeos/01 - Esperando Você.mp3` vira faixa 1, "Esperando Você", do álbum "Gêmeos". Ele remove o id do YouTube (`[1rY6FxzenSo]`), marcações como `(Official Video)` e `(MP3_320K)`, e desfaz as trocas de caracteres feitas pelo yt-dlp (`：` → `:`). Em **Propriedades**, os campos deduzidos assim aparecem marcados com •.

Na barra lateral ficam Fila, Músicas, Álbuns, Artistas, Gêneros e Pastas. Clique duas vezes numa música para tocar a lista a partir dela; o botão direito abre tocar a seguir, adicionar à fila, ir para o álbum/artista, propriedades, abrir pasta e remover. `Ctrl+F` busca em título, artista, álbum, gênero, ano e nome do arquivo, sem diferenciar acentos. Arquivos e pastas podem ser arrastados para a janela.

**Tela cheia com letra.** O botão ⛶ na barra do player (ou clicar na capa, ou `Ctrl+1`) abre o player expandido em tela cheia: o visualizador ocupa o fundo, com a capa, o título e a letra por cima. A letra sincronizada destaca a linha cantada e rola sozinha; clique numa linha para ir até ela, e role à vontade (ela volta a acompanhar em alguns segundos). A letra vem, nesta ordem: do `.lrc` ao lado da música (inclusive os `Nome_private.lrc` de alguns downloaders), das tags da música ou do [LRCLIB](https://lrclib.net) (banco aberto de letras sincronizadas; desligável em **Preferências → Letras**). Quando só existe a letra sem tempos, o Ayo pode **sincronizar pela voz**: com o `whisper-cpp` instalado (`sudo pacman -S whisper-cpp`) e o modelo de voz baixado uma vez (148 MB ou 488 MB, em Preferências → Letras), ele ouve a música no próprio computador e dá o tempo de cada linha. No menu ⋮ da tela cheia: ajustar a letra 0,5 s mais cedo ou mais tarde (fica guardado por música), buscar a letra de novo, salvar como `.lrc` ao lado da música e ligar/desligar o visualizador ao fundo. `F11` alterna entre tela cheia e janela, e `Esc` volta para a biblioteca. Em tela cheia, os botões e o cursor somem depois de 3 s parado.

**Integração com o sistema.** O Ayo Música publica o MPRIS (`org.mpris.MediaPlayer2.ayo_musica`): as teclas de mídia do Hyprland via `playerctl`, a Waybar e outros controles mostram título, artista e capa, e controlam tocar/pausar, próxima, anterior, posição, volume, repetir e aleatório. Fechar a janela com música tocando deixa o player rodando em segundo plano; abrir o Ayo Música de novo traz a janela de volta e `Ctrl+Q` encerra de vez. Quando a janela não está em foco, cada música nova aparece numa notificação com a capa. Arquivos abertos pelo gerenciador de arquivos ("Abrir com Ayo Música") ou passados na linha de comando (`ayo-musica faixa.mp3`) tocam na hora, sem entrar na biblioteca.

**Atalhos.** `Espaço` toca/pausa, `Ctrl+←/→` anterior/próxima, `Shift+←/→` volta/avança 5 s, `Ctrl+↑/↓` volume, `M` silencia, `S` ordem aleatória, `R` repetir, `Ctrl+F` busca, `Ctrl+1` abre a tela cheia, `F11` alterna tela cheia/janela, `Esc` recolhe, `Ctrl+2…5` troca de tela, `Ctrl+,` preferências e `Ctrl+?` mostra todos os atalhos.

**Playlists.** Crie playlists em **Nova playlist** na barra lateral (ou no menu principal), pelo botão direito numa ou várias músicas (**Adicionar à playlist → Nova playlist…**) ou salvando a fila atual. Para adicionar músicas, use **Adicionar à playlist** no menu de contexto ou arraste as músicas de qualquer lista até o nome da playlist na barra lateral; músicas repetidas são ignoradas. Dentro da playlist, arraste as linhas para mudar a ordem e use **Remover desta playlist** no botão direito. O menu ⋮ da playlist renomeia, exclui e exporta como M3U8 (com caminhos relativos, que funcionam em outros players); **Importar playlist (M3U)…** lê M3U, M3U8 e PLS de outros programas. Arquivos da playlist que saíram da biblioteca continuam na lista, esmaecidos.

**Identificar músicas e completar os metadados.** Em **Ferramentas → Organizar biblioteca** o Ayo mostra o que está faltando (artista, álbum, ano, número da faixa e capas que são miniaturas de vídeo) e identifica as músicas:
1. Primeiro pelo nome e pelas tags. Ele limpa o que os downloaders do YouTube deixam, como "Artista - Topic", "(Official Video)", "(MP3_160K)" e `_` no lugar de `:` ou `'`, e busca no **Deezer**.
2. Se não tiver certeza, reconhece **pelo som** com o **SongRec** (cliente livre do Shazam, que envia só a impressão digital do áudio).
3. Como reserva, usa o **MusicBrainz**, com capa do Cover Art Archive.

O Deezer completa título, artista, álbum, artista do álbum, data, faixa, gênero, ISRC e a **capa oficial em 1000×1000**. Quando título, artista e duração batem (±4 s), a correção é **gravada sozinha no arquivo**. Coletâneas, versões diferentes e durações diferentes vão para **Para revisar**, com antes e depois, a capa nova ao lado da antiga, a confiança e **Outras opções / Buscar**. Antes de gravar, as tags e a capa antigas são guardadas: **Desfazer** funciona por música ou para o lote inteiro. Também dá para usar **Identificar** no menu de contexto e o botão de **Identificar álbum** na página do álbum, que casa as faixas pelo título e pela duração. Músicas novas na pasta são identificadas sozinhas (desligável em **Preferências → Metadados**). O áudio, os nomes dos arquivos e as letras nunca são alterados.

**Som.** Em **Preferências → Som**: nivelamento de volume (usa as tags ReplayGain quando existem e mede o resto em segundo plano; no modo automático, álbuns tocados em ordem mantêm a dinâmica original), pré-amplificação, crossfade de 0 a 12 s (faixas seguidas do mesmo álbum continuam emendadas sem intervalo) e pausa suave. O **Equalizador** (no botão de relógio da barra do player ou em Preferências → Som) tem 10 bandas, presets e presets próprios. A barra do player mostra a forma de onda da música; clique ou arraste para ir a qualquer ponto. Na tela cheia, o visualizador fica ao fundo (desligável no menu ⋮ da tela cheia). Com o `cava` instalado, o Ayo roda o CAVA por baixo (reage a todo o som do computador); sem ele, usa o próprio espectro, sincronizado com o que se ouve. Em Preferências → Som dá para escolher estilo (barras, espelhado, onda, pontos), número de barras e uma faixa fina acima da barra do player. Se algum título tiver caracteres sem fonte instalada (japonês, árabe, emoji...), o app avisa qual pacote `noto-fonts-*` instalar.

**Fila, velocidade e timer.** Na **Fila**, arraste as próximas músicas para reordenar e escolha tocar na ordem, com músicas aleatórias ou com álbuns aleatórios (cada álbum inteiro, em ordem). O botão de relógio na barra do player ajusta a velocidade de 0,5× a 2× sem mudar o tom da voz, liga o timer de sono (15 min a 1h30, fim da música ou fim da fila, com o volume diminuindo aos poucos) e escolhe o dispositivo de saída. Faixas com mais de 20 minutos, como audiolivros e podcasts, voltam de onde pararam.

A fila, a música atual e a posição ficam salvas: ao abrir de novo, a última música aparece pausada no ponto onde parou. Uma reprodução só conta depois de metade da música ou 4 minutos; pular antes disso conta como pulo. São reconhecidas extensões como MP3, FLAC, OGG, OPUS, WAV, M4A, AAC e WMA, conforme os codecs instalados. Pastas ocultas e atalhos para diretórios não são percorridos.

## Dados

- Biblioteca, playlists, estatísticas, backups de tags e preferências ficam em `~/.local/share/ayo-musica/musica.sqlite3` (no Flatpak, `~/.var/app/io.github.atrzad.AyoMusica/data/ayo-musica/`), com permissão `0600`. Os arquivos de música continuam onde estão; remover da biblioteca não apaga do disco.
- Capas e respostas da internet ficam em cache em `~/.cache/ayo-musica` (no Flatpak, em `~/.var/app/io.github.atrzad.AyoMusica/cache/`). Sem o `python-mutagen`, o app usa só o nome do arquivo e da pasta.

## Desenvolvimento

```sh
make check        # testes (sem som: AYO_MUSIC_SINK=fakesink)
make smoke        # abre a janela de verdade com uma pasta de teste
```

- `ayo_musica/`: biblioteca, tags, capas, fila, motor de áudio (GStreamer), MPRIS, letras, identificação (`identify/`) e a interface (`ui/`).
- `packaging/flatpak/`: manifesto do Flatpak. `android/`: o app Android (Kotlin, Jetpack Compose e Media3).
- `AYO_SELFTEST=<arquivo>` abre o app, confere os elementos de áudio e fecha, escrevendo `OK` ou o que falhou; `AYO_DATA_DIR`/`AYO_CACHE_DIR` apontam os dados para outra pasta (inclusive no Flatpak).
