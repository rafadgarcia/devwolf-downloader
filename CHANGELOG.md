# Changelog — Devwolf Downloader

Todas as mudanças notáveis do projeto são registradas neste arquivo.

## [3.0.0] — Fase 3: Android 100% Local, DNR Instagram, Carrosséis Individuais & Otimização

### Adicionado
- **Devwolf Native Resolver (`DNR`) para Instagram**: resolvedor nativo em Kotlin com estratégias isoladas e cronometradas (`EmbedCaptionedStrategy`, `WebPageJsonStrategy`, `AuthorizedWebApiStrategy`), normalização estrita de URLs/tracking params e download HTTP direto via `SafeHttpClient` + `HttpMediaDownloader` (validação SSRF/DNS em cada redirect, TTFB e progresso real em bytes).
- **Resolvedores Locais Zero-Bloat (`GalleryDlLocalResolver`)**: suporte 100% local no dispositivo para Pinterest (imagens, vídeos e carrosséis), Reddit (galerias ordenadas, imagens e fallback `yt-dlp` para `v.redd.it`) e fallback de imagens no Twitter/X.
- **Contrato Estruturado de 11 Erros (`DevwolfErrorCode`)**: `AUTH_REQUIRED`, `PRIVATE_MEDIA`, `RATE_LIMITED`, `CAPTCHA_REQUIRED`, `MEDIA_UNAVAILABLE`, `UNSUPPORTED_MEDIA`, `CANCELLED`, `STORAGE_ERROR`, `EXTRACTOR_OUTDATED`, `EXTRACTION_FAILED`, `NETWORK_ERROR` com mensagens claras em `pt-BR`.
- **Login Opcional via WebView Oficial do Instagram (`InstagramSessionActivity`)**: captura segura de sessão apenas para `*.instagram.com` no `SecureVault` (`EncryptedSharedPreferences`) com logout em um toque.
- **Instrumentação Local (`PerformanceTracker`) e Diagnóstico**: métricas de tempo por etapa (`appOpenMs`, `clipboardDetectMs`, `engineInitMs`, `resolutionMs`, `ttfbMs`, `transferMs`, `processingMs`, `galleryPublishMs`, `totalJobMs`), contagem de requisições HTTP e cópia de log sanitizado nos Ajustes.
- **Atualizador Real do `yt-dlp` (`LocalEngineManager`)**: leitura da versão real do binário `yt-dlp` (separada do wrapper `0.18.1`), verificação automática na primeira inicialização e a cada 24h, suporte aos canais `Stable` e `Nightly`, bloqueio durante downloads ativos e rollback automático em falha.
- **Otimização de APK**: ABI splits (`arm64-v8a` ~57,7 MiB com R8 vs ~217 MiB universal) mantendo assinatura compatível para atualização direta sobre a `v2.0.0`.

### Alterado
- **Experiência 100% Local**: removida toda dependência de VPS, login obrigatório, fallback remoto e seletor Local/VPS da UI e da branch `main` (preservado integralmente em `archive/vps-hybrid` e tag `v2.0.0`).
- **Execução em Passo Único no `yt-dlp`**: removido o pré-chamado `getInfo(url)`, substituído por `--no-simulate --no-warnings --print before_dl:...` com cookies em arquivo Netscape restrito ao domínio.
- **Carrosséis Individuais na Galeria e UI**: cada foto/vídeo é salvo individualmente em `Pictures/Devwolf Downloader` / `Movies/Devwolf Downloader` com status, resolução e botões **Abrir** / **Compartilhar** próprios (proibindo registro de `.zip` no `MediaStore`).

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
