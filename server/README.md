# Servidor do Ayo Música

Conta Google e a mesma biblioteca em todos os aparelhos, como no Spotify:

- **sincronização**: playlists, curtidas, "fora do aleatório", contagens de reprodução (por aparelho), letras escolhidas ou
  sincronizadas, correções do Analisador, músicas ignoradas e preferências;
- **nuvem**: a pasta de músicas deste PC (indexada no lugar, sem copiar) e as músicas enviadas pelos aparelhos, tocadas por
  streaming ou baixadas para ouvir sem internet;
- **aparelhos**: o que cada um toca (para *continuar de onde parou*) e o controle remoto.

Node 22.5+ (`node:sqlite`), só em `127.0.0.1`; o Tailscale publica (`deploy/tailscale`). Dados em
`~/.local/share/ayo-musica-sync` (SQLite, capas, uploads).

## Instalar

```sh
cd server
npm ci
cp .env.example .env && chmod 600 .env     # preencher (abaixo)
cp deploy/ayo-musica-sync.service ~/.config/systemd/user/
systemctl --user daemon-reload && systemctl --user enable --now ayo-musica-sync
```

`.env`:

| Variável | O que é |
| --- | --- |
| `GOOGLE_WEB_CLIENT_ID` | Client ID OAuth do tipo **Web** (o app Android pede ao Google um token para ele) |
| `GOOGLE_DESKTOP_CLIENT_ID`, `GOOGLE_DESKTOP_CLIENT_SECRET` | Client OAuth do tipo **Desktop** (o app do PC; o Google não trata esse segredo como confidencial) |
| `GOOGLE_CLIENT_IDS` | Os dois IDs acima, separados por vírgula (tokens aceitos) |
| `ALLOWED_EMAILS` | Quem pode entrar (e-mails Google, separados por vírgula) |
| `LIBRARY_FOLDERS` | Pastas deste PC que são a nuvem de uma conta: `email=/caminho;email2=/outro` |

No Google Cloud (Google Auth Platform): público "External" com os e-mails como usuários de teste; clients **Web**,
**Desktop** e **Android** (pacote `io.github.atrzad.ayomusica`, SHA-1 da chave de assinatura do APK).

## Publicar pelo Tailscale

```sh
cd deploy/tailscale
printf 'TS_AUTHKEY=tskey-...\n' > .env && chmod 600 .env
docker compose up -d                       # → https://ayo-musica.<sua-tailnet>.ts.net
```

## Administração

```sh
node --env-file=.env src/admin.js users                  # contas e último uso
node --env-file=.env src/admin.js token email@gmail.com  # uma sessão de teste (sem Google), para apps de teste
npm test
```
