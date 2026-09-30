# Changelog

## 0.3.0 — Android

- **Som**: equalizador com os presets do celular e ajuste por banda, graves, velocidade de 0,5× a 2× (a voz
  mantém o tom) e timer de sono (15 min a 1h30, com o volume baixando aos poucos, ou no fim da música).
- **Visualizador** ao fundo da tela cheia, como o CAVA do PC. Pede a permissão de gravar áudio só quando ligado,
  com explicação: o microfone não é usado.
- **Favoritas** (coração na tela cheia ou menu ⋮) e **listas automáticas**: Favoritas, Mais tocadas, Tocadas
  recentemente e Adicionadas recentemente. Uma música conta como tocada depois de metade (ou 4 minutos).
- Letras embutidas também em **FLAC** e **M4A**, além do MP3.
- Corrigido: a capa do mini player podia continuar a da música anterior; letras com caracteres de controle
  escondidos.

## 0.2.0 — aplicativo próprio

- O Ayo Música saiu da suíte Ayo Desk e virou um app próprio (`io.github.atrzad.AyoMusica`), com banco
  e cache próprios; na primeira vez, importa a biblioteca, as playlists, as estatísticas, os backups de tags e
  as capas do Ayo Desk, sem alterar nada lá.
- Pacote Flatpak (GNOME 50): usa o `cava`, o `songrec`, o `whisper-cpp` e o `pactl` do sistema quando existem.
- Ícone próprio, atalho de menu e dados AppStream.
- **Android** (novo, nativo em Kotlin + Jetpack Compose + Media3): biblioteca do celular (músicas, álbuns,
  artistas, busca sem acento), playlists, fila, tocar a seguir, aleatório e repetir; toca em segundo plano com
  controles na notificação, na tela de bloqueio e no fone; retoma a fila onde parou; tela cheia com capa e letra
  sincronizada (tags ID3 do MP3 ou LRCLIB) com ajuste de atraso por música; tema preto e branco claro/escuro.

## Herdado do Ayo Desk (até 0.2.0)

- Biblioteca por tags com mutagen, inferência pelo nome do arquivo/pasta e leitura incremental.
- Capas em cache, visões de álbuns, artistas (com colaboradores), gêneros e pastas; busca sem acentos.
- Nova interface: barra lateral adaptável, barra do player, Tocando agora, fila e propriedades.
- Motor de reprodução com gapless, fila com tocar a seguir, aleatório sem repetição e repetir uma.
- Sessão retomada ao abrir, contagem de reproduções/pulos e favoritas.
- Migrações versionadas do banco de dados e tabela de preferências.
- MPRIS (teclas de mídia, playerctl, Waybar), notificações de troca de música e reprodução em segundo plano.
- Fila com arrastar para reordenar e álbuns aleatórios; atalhos de teclado; timer de sono com fade-out.
- Velocidade de 0,5× a 2× mantendo o tom, escolha do dispositivo de saída e retomada de faixas longas.
- Abrir arquivos pelo gerenciador de arquivos ou pela linha de comando.
- Playlists locais: criar, renomear, excluir, adicionar pelo menu ou arrastando, reordenar, salvar a fila,
  importar M3U/M3U8/PLS e exportar M3U8.
- Som: nivelamento de volume (tags ReplayGain ou medição própria em segundo plano, modos faixa/álbum/automático),
  equalizador de 10 bandas com presets, crossfade que respeita álbuns em ordem, pausa suave,
  barra de progresso em forma de onda e visualizador de espectro.
- Visualizador estilo CAVA embutido (usa o `cava` instalado; barras, espelhado, onda ou pontos; faixa opcional
  acima do player) e aviso de fontes faltando para títulos em outras línguas.
- Organizar biblioteca: identifica músicas pelo nome/tags (Deezer), pelo som (SongRec/Shazam) e pelo MusicBrainz;
  completa artista, álbum, ano, faixa, gênero, ISRC e troca miniaturas de vídeo pela capa oficial. Grava sozinho
  quando há certeza, manda o resto para revisão e guarda backup para desfazer. Identificar álbum inteiro.
- Corrigido: seek durante a troca gapless de faixa podia travar o player.
- Tela cheia no lugar de "Tocando agora": visualizador ao fundo, capa e letra sincronizada que rola sozinha,
  clique para pular, ajuste de atraso por música e salvar como `.lrc`. Letras do `.lrc`/`_private.lrc` ao lado,
  das tags ou do LRCLIB; letras sem tempos podem ser sincronizadas pela voz com o whisper.cpp, sem enviar nada.
- Corrigido: o visualizador não voltava depois de fechar e reabrir a janela, nem quando o `cava` fechava sozinho.
