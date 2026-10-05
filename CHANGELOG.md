# Changelog

## 2.1.0 — Troca entre aparelhos sem travar

- Desktop: a janela travava a cada play, pausa ou comando do controle remoto (para saber se uma música era da nuvem,
  percorria as 1.500 faixas e lia a pasta de downloads a cada vez). Agora isso fica num índice em memória.
- As músicas corrigidas pelo Analisador no celular viajam com a identidade das tags do arquivo: "Tocar lá" e
  "Continuar aqui" acham a mesma música no computador (antes ficavam sem fazer nada).
- "Tocar lá" com a biblioteca inteira na fila mandava as 300 primeiras músicas, não as da vez: o outro aparelho
  começava na música errada.
- O celular deixa a biblioteca pronta para receber uma fila de outro aparelho: começa a tocar em cerca de 1 segundo.
- Dispositivos abre por inteiro (os botões Continuar aqui e Tocar lá ficavam escondidos com a janela pela metade).
- Desktop: aviso quando as músicas do outro aparelho não estão neste computador nem na nuvem.

## 2.0.0 — Conta Google, nuvem e aparelhos conectados (Android e desktop)

Como no Spotify, a mesma biblioteca em todos os aparelhos. O servidor fica no seu PC (`server/`, publicado pelo
Tailscale); veja `server/README.md`.

- Entrar com o Google (Android: Configurações → Conta e nuvem; desktop: Menu → Conta e nuvem).
- Sincronização: playlists (com imagem e descrição), curtidas, "fora do aleatório", contagens de reprodução (cada
  aparelho as suas, somadas em "Mais tocadas"), letras escolhidas ou sincronizadas, correções do Analisador, músicas
  ignoradas e preferências. A mesma música é reconhecida em cada aparelho por artista, título e duração; o que se
  refere a uma música que o aparelho não tem fica guardado até ela aparecer.
- Nuvem: a pasta de músicas do PC (indexada no lugar, sem copiar) e o que os aparelhos enviarem. Filtros Tudo · Neste
  aparelho · Nuvem; tocar por streaming; baixar para ouvir sem internet; enviar músicas; tirar da nuvem. Playlists
  feitas no PC tocam no celular pela nuvem.
- Aparelhos (como o Spotify Connect): "Continuar de <aparelho>" ao abrir, "Continuar aqui", "Tocar lá" e controle
  remoto (tocar/pausar, anterior, próxima).
- A partir desta versão os dois apps têm o mesmo número de versão.

## 1.0.0 — Relatório de erros e sincronização pela voz estável (Android)

A partir daqui: funcionalidade nova sobe a versão inteira (2.0.0…), correção sobe 0.1 (1.1.0…).

- Relatório de erros (Configurações → Relatório de erros): o app guarda um diário do que fez e dos erros, o rastro
  dos travamentos e, ao abrir de novo, por que o Android o fechou (erro nativo, falta de memória, sem resposta).
  "Enviar relatório" anexa tudo (com o log do sistema do próprio app) a um e-mail, WhatsApp etc. Depois de um
  fechamento inesperado, o app oferece enviar ao abrir.
- Sincronizar e transcrever pela voz não derrubam mais o app:
  - o texto do modelo vinha às vezes com um caractere acentuado partido entre dois pedaços (ou com emoji), e a
    ponte nativa passava isso de um jeito que o Android encerra o app; agora os caracteres são remontados e o
    texto vem como bytes;
  - uma sincronização nova espera a anterior parar de verdade (antes as duas podiam rodar juntas e uma apagava o
    que a outra usava);
  - a checagem do processador exige as instruções da voz em todos os núcleos (há celulares que misturam núcleos com
    e sem elas, e um núcleo sem elas fechava o app);
  - no máximo 4 núcleos para a voz, deixando o resto para a tela.
- Ler o áudio para a voz ficou cerca de 4 vezes mais rápido e mostra o progresso (antes parecia travado).
- Avisos aparecem acima do botão flutuante de abas.

## 0.10.0 — Reconhecer pelo som: Shazam e AcoustID (Android)

- Shazam: quando as tags não bastam, o Analisador reconhece 12 segundos do meio da música (só a impressão digital do
  som é enviada). Acha até rap independente brasileiro que nenhuma outra fonte tinha. A assinatura é a mesma do
  SongRec, byte a byte.
- AcoustID: com a chave gratuita da pessoa (Configurações → Analisador), reconhece pelos dois primeiros minutos com o
  Chromaprint e completa ano e gênero no MusicBrainz.
- "Corrigir informações" ganhou "Reconhecer pelo som".
- Som e tags concordando: aplica sozinho; discordando: vai para Revisar.
- Licença: o app Android passa a ser GPL-3.0 (inclui o algoritmo do SongRec); o desktop continua MIT. Créditos em
  `android/NOTICE.md` e nas Configurações.

## 0.9.0 — Mais fontes de letras e transcrição pela voz (Android)

- Letras: além do LRCLIB, o NetEase (letras sincronizadas de um catálogo enorme, inclusive músicas brasileiras como
  Djavan e Anitta) e o lyrics.ovh (texto, como último recurso). A busca manual mostra LRCLIB e NetEase juntos, com a
  fonte de cada resultado.
- Transcrever pela voz: quando nenhuma fonte tem a letra, o app escreve a letra a partir do canto, já com os tempos,
  no próprio celular (mesmo modelo da sincronização pela voz; funciona sem internet). Pode ter palavras erradas.
- O idioma da transcrição vem do que o modelo detecta no meio da música (a introdução costuma ser instrumental).
- Sincronização e transcrição pela voz ignoram as anotações do modelo, como “[Música]”.

## 0.8.0 — Analisador que lembra, AMOLED, tela cheia e lista negra do aleatório (Android)

- Analisador: os resultados ficam salvos; "Continuar análise" segue de onde parou e só procura as músicas ainda não
  verificadas. Corrigida, aceita ou desfeita, cada música fica numa aba só (antes, durante a análise, uma correção à
  mão ou "Aceitar todas" voltava para a lista anterior e a tela ficava pulando).
- Ignorar em Revisar e Não achadas: a música sai das listas e não é mais analisada; a aba Ignoradas traz de volta.
  "Procurar de novo as não achadas" refaz só essas.
- "Aceitar todas" mostra o progresso e não roda duas vezes ao mesmo tempo.
- Tema AMOLED (preto puro) em Temas → Claro ou escuro, com qualquer paleta.
- Tela cheia em Configurações → Modo: esconde a barra de status e a de navegação.
- Lista negra do aleatório: "Não tocar no aleatório" (menu ⋮ da música, do Tocando agora ou na seleção múltipla). Essas
  músicas ficam de fora quando você ouve no aleatório, mas tocam quando escolhidas. A lista "Fora do aleatório" em
  Playlists mostra quais são.

## 0.7.0 — Seleção múltipla e playlists completas (Android)

- Segurar uma música na tela inicial abre a seleção múltipla, com caixas de marcar: tocar, tocar a seguir, adicionar
  à fila, adicionar à playlist (ou criar uma com elas), curtir, selecionar todas. O ⋮ continua com as opções de uma
  música.
- Dentro da playlist, "Adicionar músicas" abre a biblioteca com busca e caixas de marcar ("já na playlist" marcado);
  criar uma playlist nova já abre essa tela.
- Editar playlist: imagem (escolhida na galeria, sem pedir permissão), título e descrição; a imagem aparece no
  cabeçalho e na lista de playlists. Excluir uma playlist agora pede confirmação.
- Tela inicial: a busca fica numa linha só e a quantidade de músicas foi para o fim da lista.
- Analisador: música corrigida à mão sai de Revisar e de Não achadas e aparece em Corrigidas ("corrigida à mão").
- Avisos de "adicionadas à playlist" contam só as músicas novas (as que já estavam são puladas).

## 0.6.0 — Mais fontes e correção manual (Android)

- Identificação com três fontes, sem conta nem chave: Deezer, Apple Music (iTunes) e MusicBrainz (capas do Cover
  Art Archive). O Analisador consulta Apple Music e MusicBrainz quando o Deezer não tem certeza.
- Nova tela "Corrigir informações" (menu ⋮ de qualquer música, Tocando agora → ⋮, ou Procurar no Analisador):
  busca por título e artista nas fontes escolhidas, mostra capa, álbum, ano e duração (a diferença para a sua
  música fica destacada) e, ao tocar num resultado, deixa revisar e editar título, artista, álbum, artista do álbum,
  ano, gênero e capa antes de salvar. Também dá para editar à mão e restaurar as tags do arquivo.
- Analisador: botões Aceitar/Desfazer maiores, "Aceitar todas" na aba Revisar e Procurar em cada música.
- As correções continuam só no app: os arquivos não são alterados.
- Corrigido: o cartão da música tocando só mostrava uma correção na música seguinte.

## 0.5.6 — Gestos invertidos

- Arrastos invertidos, no Tocando agora, no cartão da tela inicial e no modo carro: um para a esquerda passa a
  música; um para a direita volta ao começo dela; dois seguidos para a direita voltam uma música.

## 0.5.5 — Gestos certos e modo simplificado com barra

- Gestos do Tocando agora: um arrasto para a direita avança; um para a esquerda volta ao começo da música; dois
  seguidos para a esquerda voltam uma música. Arrastos que começam na borda da tela não viram mais o "voltar" do
  Android (que fechava o player ou saía do app).
- Modo simplificado: a música tocando sai do topo e vira uma barra embaixo, acima das abas, com anterior,
  tocar/pausar e próxima.
- Modo normal: o botão de trocar de abas flutua por cima das músicas, sem a faixa de fundo.

## 0.5.4 — Tutorial

- Android: tutorial "Como usar" na primeira vez que o app abre (e em Configurações → Como usar), com o gesto de
  cada tela animado: tela inicial, botão de abas, gestos do Tocando agora, aleatório/repetir/curtir, letra e modos.
- A legenda dos gestos saiu do Tocando agora (agora está no tutorial).

## 0.5.3 — Modo simplificado com botões

- Modo simplificado: botões de música anterior e próxima e um botão Letra no Tocando agora, e botão de próxima
  no canto da tela inicial (os gestos continuam valendo).
- Tela de letra: deslizar para cima em qualquer lugar volta para o Tocando agora (com letra sem tempos, ao chegar
  no fim dela); o ajuste de tempo ganhou uma linha própria (Mais cedo / Mais tarde).
- Corrigido: o teclado da busca ficava aberto por cima do player; a letra podia não aparecer logo depois de abrir
  o app.

## 0.5.2 — Bibliotecas grandes

- Android rápido com bibliotecas enormes (testado com 12 000 músicas): ordenar, agrupar (álbuns, artistas,
  gêneros, pastas), buscar e contar as listas automáticas saíram da thread da tela; a busca espera você parar de
  digitar; uma expressão regular que era recompilada centenas de milhares de vezes agora é compilada uma vez.
- Tocar uma lista gigante começa na hora: o player recebe a música escolhida e as próximas 300 e o resto entra em
  segundo plano, em lotes (antes a tela travava ~1,2 s); a fila só é montada quando você abre a fila e é salva em
  segundo plano (no máximo 3 000 músicas em volta da atual).
- Capas: no máximo 4 sendo lidas ao mesmo tempo, as que saem da tela são canceladas, e o cache usa 1/8 da memória
  do app.

## 0.5.1 — Botão de abas

- Android: as abas abrem em leque centralizado acima do botão redondo, com qualquer número de abas; a direção do
  arraste escolhe a aba (não precisa acertar o ponto) e a aba apontada cresce, destacada.

## 0.5.0 — Novo app Android e letras que funcionam

- **Android redesenhado** a partir do esboço: título AYO PLAYER (abre as Configurações), a música tocando no
  canto (arraste ←/→ para voltar/avançar), botão redondo que troca de aba segurando e arrastando (barra fixa no
  modo simplificado), abas Gêneros e Pastas e busca dentro da lista.
- **Tocando agora** novo: voltar, curtir (Curtidas), capa, aleatório com animação das setas, repetir (1 toque:
  lista/álbum, 2 toques: a música) e gestos: ← reinicia (duas vezes volta uma música), → avança, ↓ abre a letra.
- **Letras**: a busca automática entende tags de downloads do YouTube (em 40 músicas reais, de 18 para 30 com
  letra sincronizada), busca manual no LRCLIB, **sincronizar tocando** (toque a cada linha) e **sincronizar pela
  voz no celular** com o whisper.cpp (modelo de 60 MB baixado uma vez; nada é enviado).
- **Configurações**: Analisador de músicas (Deezer: título, artista, álbum, ano, gênero e capa oficial, guardados
  no app com Desfazer), Equalizador, Modo (normal, **carro** com tela deitada e botões gigantes, simplificado),
  Temas e Página inicial (quais abas aparecem, ordem e aba inicial).
- **Temas do kitty** (PC e Android): Catppuccin, Gruvbox, Tokyo Night, Solarized, One Dark, Rosé Pine, Everforest
  e Kanagawa, e o tema **Pixel** (fonte pixelada e cantos retos no celular).

## 0.4.0 — Temas (PC e Android)

- **10 temas novos**, cada um com versão clara e escura: Nórdico, Oceano, Floresta, Vinho, Âmbar, Lavanda, Rosa,
  Sépia, Menta e Drácula, além do Preto e branco. As mesmas cores nos dois apps (`data/themes/themes.json`),
  todas com contraste conferido nos testes.
- **PC**: Preferências → Geral → Aparência, com Automático/Claro/Escuro guardado entre sessões. O tema
  **Papel de parede (wallust)** usa as cores do wallust e muda sozinho quando o papel de parede muda. O
  visualizador, a forma de onda e a linha cantada da letra ficam na cor do tema.
- **Android**: botão de paleta na barra de cima, com prévia de cada tema, Automático/Claro/Escuro e
  **Cores do papel de parede** (Android 12+).

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
