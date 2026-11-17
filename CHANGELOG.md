# Changelog — Devwolf Downloader

Todas as mudanças notáveis do projeto são registradas neste arquivo.

## [2.0.0] — Fase 2: App Híbrido Android

### Adicionado
- Rebranding oficial para **Devwolf Downloader** (`com.devwolf.downloader`).
- Camada nativa Android via Capacitor (`android/`) com engine local on-device (`youtubedl-android`: `yt-dlp` + `FFmpeg` + `aria2c`).
- Plugin nativo `DevwolfNative` em Kotlin expondo:
  - Extração e download local com progresso real (bytes, percentual, velocidade/ETA) e cancelamento imediato.
  - **Modo Status (WhatsApp)**: inspeção via `ffprobe`/FFmpeg, remux sem recompressão (`-c copy`) quando já compatível com H.264/AAC MP4, ou transcodificação com preservação de proporção (`libx264` + `aac` + `-movflags +faststart`), sem marca d'água e com corte opcional em segmentos de N segundos.
  - Integração nativa com `MediaStore` (`Devwolf Downloader` em `Movies/`, `Pictures/` e `Music/`), `FileProvider` para Sharesheet Android (`ACTION_SEND` / `ACTION_SEND_MULTIPLE`), abrir e excluir mídia.
  - Receptor nativo `ACTION_SEND` (`text/plain`) no menu de compartilhamento do Android com auto-início e retorno rápido ao app de origem.
  - Execução em segundo plano com `DevwolfDownloadService` (Foreground Service `dataSync`), fila persistente em disco e notificações nativas (progresso com cancelar; conclusão com **Compartilhar no Status / App** e **Abrir**).
  - Atualizador e rollback local do `yt-dlp` via releases oficiais no GitHub, bloqueado durante downloads ativos.
  - Cofre criptografado de sessões/cookies por plataforma no dispositivo (`EncryptedSharedPreferences` + Android Keystore), nunca enviado à VPS.
- Suporte híbrido no backend e frontend: CORS/Bearer token para Capacitor (`https://localhost`), Modo Status no worker, orquestração `Automático` / `Local (Dispositivo)` / `Remoto (VPS)` e workflow CI/CD `.github/workflows/ci.yml`.

## [1.0.0] — Fase 1: Baseline Self-Hosted PWA

### Adicionado
- Backend FastAPI + fila persistente SQLite WAL + worker separado não-root (`yt-dlp`, `gallery-dl`, `cobalt`).
- Autenticação pessoal com Argon2id, cookies `HttpOnly`/`SameSite=Lax`, CSRF e rate limiting.
- Validador rigoroso de URL, normalização de tracking params e proteção SSRF (allowlist `config/routes.yaml` + validação DNS/redirects).
- Progresso real via SSE com fallback automático para polling e entrega autenticada de arquivos (carrosséis em ZIP).
- Frontend PWA em `pt-BR` com clipboard inteligente, deduplicação de 10 minutos, Web Share Target (`POST /share-target`), Histórico, Motores e Ajustes.
- Infraestrutura Docker Compose, scripts operacionais (`ops/update-cobalt.sh` com systemd timer e rollback automático, `backup.sh`, `check-engine-updates.sh`, `update-worker.sh`), documentação em `docs/` e grafo `graphify-out/`.
