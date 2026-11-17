# Devwolf Downloader — Downloader Universal Híbrido (Android + Self-Hosted VPS)

**Copiou. Abriu. Baixou.**

O **Devwolf Downloader** (`com.devwolf.downloader`) é um aplicativo híbrido (App Android nativo via Capacitor + PWA Web self-hosted) para baixar vídeos, fotos, áudios e carrosséis públicos autorizados de múltiplas plataformas (Instagram, Facebook Reels/vídeos, TikTok, YouTube, X/Twitter, Reddit e Pinterest), operando com **motor local no dispositivo (`yt-dlp` + `FFmpeg` + `aria2c`)**, **orquestração remota na VPS (`Cobalt` + `yt-dlp` + `gallery-dl`)** e **Modo Status (WhatsApp)**.

---

## Principais Recursos

- **App Android Nativo (`com.devwolf.downloader`) + PWA (`pt-BR`)**: tema escuro padrão (`#101012` / `#A3E635`), área de toque >= 44 px e tema claro opcional.
- **Execução Híbrida (`Automático` / `Local` / `VPS`)**:
  - **Motor Local (Android)**: executa `yt-dlp` + `FFmpeg` diretamente no aparelho via `youtubedl-android`, funcionando offline da VPS com progresso real em tempo real, Foreground Service (`dataSync`) e notificações com **Cancelar**, **Compartilhar no Status / App** e **Abrir**.
  - **Motor Remoto (VPS)**: conecta via sessão/Bearer token à sua instância self-hosted com Cobalt, `yt-dlp` e `gallery-dl` (baixando automaticamente para o álbum da galeria no Android).
  - **Modo Automático**: prioriza o motor local no Android com fallback automático para a VPS em falhas técnicas recuperáveis.
- **Modo Status (WhatsApp)**: converte vídeos incompatíveis para MP4 (`H.264` + `AAC` + `faststart`) preservando a proporção original (sem recompressão via `-c copy` quando já compatível), sem marca d'água e com corte opcional em segmentos de N segundos.
- **Receptor Nativo de Compartilhamento (`ACTION_SEND`) & Galeria (`MediaStore`)**: aparece diretamente no menu Compartilhar do Android e salva mídias no álbum `Devwolf Downloader` (`Movies/`, `Pictures/`, `Music/`).
- **Atualização & Rollback Seguros**: atualizador local de `yt-dlp` no Android (bloqueado durante downloads ativos, com backup/rollback) e timer `systemd` no host para Cobalt (`ops/update-cobalt.sh`) com rollback automático.
- **Cofre Local Criptografado**: suporte opcional a sessões/cookies por plataforma no Android via `EncryptedSharedPreferences` + Android Keystore (nunca enviado à VPS).
- **Clipboard Inteligente & Proteção SSRF**: leitura opcional local no cliente; allowlist estrita em [`config/routes.yaml`](config/routes.yaml), rejeição de IPs privados/loopback/link-local e validação de redirects/DNS.

---

## Arquitetura

```text
PWA (React/Vite/TS) ──► FastAPI (Auth Argon2id, CSRF, SQLite WAL, SSE)
                              │
                              ▼
                     Worker Python (não-root)
                 ┌──────┼──────────────┐
                 ▼      ▼              ▼
              yt-dlp  gallery-dl    Cobalt existente
                                       ▲
                    Host systemd timer ┘ (ops/update-cobalt.sh + rollback)
```

Decisões detalhadas em [`DECISIONS.md`](DECISIONS.md) e contratos em [`AGENTS.md`](AGENTS.md).

---

## Início Rápido

### 1. Pré-requisitos
- Docker + Docker Compose v2
- Instância existente do Cobalt (acessível por rede Docker ou endpoint interno)
- Proxy reverso com HTTPS (Nginx, Caddy ou Traefik) para uso completo em dispositivos móveis
- No host (para os scripts em `ops/`): `bash`, `python3`, `flock`, `curl` e permissão Docker/systemd

### 2. Configuração
```bash
cp .env.example .env
chmod 600 .env
# Edite .env com APP_URL, ADMIN_PASSWORD, SESSION_SECRET, COBALT_API_URL, COBALT_HOST_HEALTH_URL e COBALT_COMPOSE_FILE
```

### 3. Executar com Docker Compose
```bash
mkdir -p data/db data/storage data/ops-status backups
# Necessário para que o usuário não-root do container (UID/GID 10001) escreva no SQLite/storage
sudo chown -R 10001:10001 data/db data/storage

docker compose build
docker compose up -d
```

Consulte o passo a passo completo (incluindo comandos de inventário read-only da VPS, `compose.cobalt-net.yml` e proxy SSE) em [`docs/deployment.md`](docs/deployment.md).

---

## Limites Padrão Configuráveis (`.env`)

| Variável | Padrão | Finalidade |
| --- | --- | --- |
| `MAX_CONCURRENT_JOBS` | `2` | Trabalhos simultâneos no worker |
| `MAX_QUEUED_JOBS_PER_USER` | `20` | Limite máximo de fila por usuário |
| `MAX_JOB_SIZE_MB` | `500` | Tamanho máximo por job em streaming |
| `MAX_CAROUSEL_ITEMS` | `50` | Máximo de itens por carrossel (`gallery-dl` / `picker`) |
| `MAX_JOB_DURATION_SECONDS` | `600` | Timeout máximo total por job (10 min) |
| `MIN_FREE_DISK_MB` | `2048` | Espaço livre mínimo para aceitar novos jobs (2 GB) |
| `FILE_RETENTION_HOURS` | `24` | Retenção de arquivos baixados |
| `HISTORY_RETENTION_DAYS` | `30` | Retenção do histórico no SQLite |

---

## Operação, Backup e Rollback

- **Atualização do Cobalt (timer `systemd` 30m via `OnCalendar=*:0/30`, personalizável com `systemctl edit cobalt-update.timer`)**: [`docs/maintenance.md`](docs/maintenance.md) e [`ops/update-cobalt.sh`](ops/update-cobalt.sh)
- **Verificação e atualização de `yt-dlp` / `gallery-dl`**:
  ```bash
  bash ops/check-engine-updates.sh
  bash ops/update-worker.sh --apply
  ```
- **Backup consistente (SQLite online + configs)**:
  ```bash
  bash ops/backup.sh
  ```
- **Diagnóstico e limitações reais** (Clipboard HTTPS/gesto, iOS sem Web Share Target, bloqueios de provedores): [`docs/troubleshooting.md`](docs/troubleshooting.md)

---

## Desenvolvimento Local e Testes

### Backend (Python 3.11+)
```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # ou .venv\Scripts\activate no Windows
pip install -r requirements.txt
pytest -q
```

### Frontend (Node 20+)
```bash
cd frontend
npm install
npm test
npm run build
```

### App Android Híbrido (JDK 21 + Android SDK + Capacitor)
```bash
cd frontend
npm install
npm run build
npx cap sync android

cd ../android
./gradlew testDebugUnitTest assembleDebug
# APK gerado em: android/app/build/outputs/apk/debug/app-debug.apk
```
Para instalar via USB (`adb`):
```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

---

## Escopo de Segurança e Privacidade
- Uso estritamente pessoal autenticado (Argon2id + cookie `HttpOnly`/`SameSite=Lax`/`Secure` + CSRF + rate limiting).
- Sem cadastro público, sem anúncios, sem rastreadores e sem cache de API/mídia no Service Worker.
- Não contorna DRM, contas privadas ou desafios anti-bot. Destinado exclusivamente a conteúdo próprio ou com autorização do titular.
