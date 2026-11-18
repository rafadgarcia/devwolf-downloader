# Changelog — Devwolf Downloader

Todas as mudanças notáveis do projeto são registradas neste arquivo.

## [3.0.0-rc.3] — Pinterest Vídeo, TikTok `yt-dlp`, Threads Nativo, Contas Multiplataforma & Diagnóstico Completo

### Adicionado
- **Suporte Nativo ao Threads (`threads.net` / `threads.com`)**:
  - Extração/normalização de URLs (`UrlExtractor.kt`, `frontend/src/utils/url.ts`), badge `✓ Threads detectado`, filtro no Histórico e entrada na tabela de roteamento (`DNR -> yt-dlp`).
  - Novo resolvedor `ThreadsNativeResolver.kt` (`ThreadsWebPageJsonStrategy` -> `ThreadsEmbedMetaStrategy`) com suporte a vídeos, fotos e carrosséis mistos, além de sessão opcional isolada a `*.threads.net` / `*.threads.com`.
- **Gerenciador Multiplataforma "Contas e Sessões" (`InstagramSessionActivity.kt`, `DevwolfNativePlugin.kt`, `SecureVault.kt`, `SettingsView.tsx`)**:
  - Cartões independentes nos Ajustes para **Instagram**, **Reddit** e **Threads** com status (`Conectado` / `Não conectado`), método, última atualização, **Conectar** e **Encerrar e excluir sessão** isolados por domínio.
  - Suporte oficial ao **Reddit OAuth2 Installed App PKCE** (`authorize.compact` + `oauth.reddit.com` com `client_id` do usuário em `developers.reddit.com`, sem client secret) e WebView padrão do Reddit / Threads / Instagram.
  - Método nativo `openPlatformLoginWebView({ platform, redditClientId? })` preservando `openInstagramLoginWebView()`.
- **Mensagens `AUTH_REQUIRED` e Ação Inline por Plataforma (`errorContract.ts`, `JobCard.tsx`, `ResolverContract.kt`)**:
  - `getErrorContract` / `getUserFacingErrorMessage` e `ErrorClassifier.authRequiredMessageForPlatform` exibem orientações específicas para Reddit, Threads e Instagram (nunca atribuindo erros do Reddit ou Threads ao Instagram).
  - `JobCard.tsx` exibe botão contextual **Autenticar no Reddit** / **Autenticar no Instagram** / **Autenticar no Threads** direcionando para o cartão correspondente em **Contas e Sessões**.
- **Diagnóstico Sanitizado de 9 Campos (`PerformanceTracker.kt`, `DevwolfDownloadService.kt`, `SettingsView.tsx`)**:
  - Relatório e log sanitizado passam a incluir: `platform`, `engineUsed`/`engineVersion`, `strategyAttempts`, `httpStatus`, `exitCode`, `rawErrorSanitized`, `errorCode`, `mediaTypeIdentified` e tempos (`resolutionMs`/`transferMs`/`totalJobMs`), removendo cookies, `Bearer` tokens, `access_token` e queries assinadas.

### Corrigido
- **Pinterest Vídeo (`GalleryDlLocalResolver.kt`, `HttpMediaDownloader.kt`)**:
  - Impedida a devolução de thumbnail (`og:image` / `images.orig`) como sucesso em Pins de vídeo/HLS; seleção do maior `.mp4` direto quando disponível e delegação limpa via `EXTRACTION_FAILED` para o extrator HLS/vídeo do `yt-dlp`, com validação de `Content-Type` e magic bytes.
- **TikTok `EXTRACTOR_OUTDATED` & Runtime `yt-dlp` (`ResolverContract.kt`, `LocalEngineManager.kt`, `DevwolfDownloadService.kt`)**:
  - Corrigido falso positivo de `EXTRACTOR_OUTDATED` causado pelo rodapé padrão do `yt-dlp` (`"Make sure you are using the latest version"` / `"unable to extract"`).
  - `LocalEngineManager` agora lê diretamente a versão do binário zipapp (`yt_dlp/version.py`) após `ensureYtdlpInitialized`, garantindo persistência e relatório preciso após reinicializações e rollbacks sem corromper as preferências internas do `youtubedl-android`.
  - `DevwolfDownloadService.buildYoutubeDlRequest` deixa de sobrescrever `--user-agent` no TikTok/YouTube, preservando o `User-Agent` nativo dos extratores do `yt-dlp`, e grava `scoped.cookies.txt` também para `threads` (`.threads.net`/`.threads.com`) e `reddit`.
- **Reddit WAF/Auth & Remoção de Host Cycling (`GalleryDlLocalResolver.kt`)**:
  - Removido fallback para `old.reddit.com`; respostas 401/403 sem sessão/OAuth retornam `AUTH_REQUIRED` contextualizado para o Reddit.
- **Build & Versionamento (`android/app/build.gradle`, `frontend/package.json`)**:
  - Atualizado para `versionCode 30003` e `versionName "3.0.0-rc.3"` mantendo assinatura compatível com `v2.0.0` / `v3.0.0-rc.2`.

## [3.0.0-rc.2] — Correções de Diagnóstico RC1 (Instagram & Reddit)

### Corrigido
- **Instagram — Eliminação de falsos positivos de `CAPTCHA_REQUIRED` (`InstagramParsers.kt`)**:
  - `InstagramParsers.classifyFailure` agora remove `<script>`, `<style>`, `<noscript>`, `<meta>` e `<link>` antes de inspecionar o texto visível do DOM, evitando que strings de bootloader JS do Polaris (`"arkose_captcha"`, `"google_recaptcha"`, `"/challenge/"`) disparem `CAPTCHA_REQUIRED` terminal em páginas HTML normais.
  - Detecção de desafio anti-robô restrita a redirecionamentos de path (`/challenge/`, `/checkpoint/`), payloads JSON estruturados (`challenge_required`, `checkpoint_required`, `challenge_url`, objeto `challenge`) ou formulários/widgets anti-robô visíveis no DOM.
  - `InstagramParsers.extractPageContext` passa a extrair `mediaId` também das meta tags `<meta property="al:ios:url">` e `<meta property="al:android:url">` (`media?id=...`).
- **Instagram — Ordem *Session-First* e Alinhamento de `User-Agent` (`InstagramNativeResolver.kt`, `DevwolfDownloadService.kt`)**:
  - Quando há sessão autenticada (`hasSession == true`), o DNR executa `WebPageJsonStrategy` -> `AuthorizedWebApiStrategy` -> `EmbedCaptionedStrategy` (mantendo `EmbedCaptionedStrategy` primeiro apenas em modo anônimo) e evita que estratégias secundárias/anônimas envenenem `sawAuthWall` ou abortem por HTTP 404 fora da página principal.
  - No fallback `yt-dlp` (`DevwolfDownloadService.buildYoutubeDlRequest`), `--user-agent` agora recebe o mesmo `User-Agent` da sessão WebView armazenada no `SecureVault` (`resolveYtdlpUserAgent`), alinhando-o ao `sessionid` gravado em `scoped.cookies.txt`.
- **Reddit — Diferenciação de HTTP 403 WAF/Fastly e Links `/r/<sub>/s/<id>` (`GalleryDlLocalResolver.kt`, `ResolverContract.kt`, `UrlExtractor.kt`)**:
  - `GalleryDlLocalResolver.classifyRedditHttpError` (e `ErrorClassifier.fromHttpStatus`) passa a classificar bloqueios WAF/Fastly (`"whoa there, pardner!"`, `"blocked by network security"`, `"403 blocked"`, HTML 403 genérico) como `EXTRACTION_FAILED` recuperável, acionando fallback para `old.reddit.com` e em seguida para `yt-dlp`, mantendo `PRIVATE_MEDIA` apenas para respostas explícitas de comunidade/post privado (`"reason": "private"`, `"subreddit_type": "private"`, `"this community is private"`).
  - Suporte completo a links móveis `/r/<sub>/s/<share_id>`: remoção de novos parâmetros de rastreamento (`context`, `rdt`, `ref_source`) em `UrlExtractor`, extração de `<link rel="canonical">` / `og:url` quando `/s/<id>` devolve HTML 200 sem 3xx, proibição de anexar `.json` a `/s/<id>` não resolvido (`buildRedditJsonUrl` retorna `null`) e encaminhamento via `EXTRACTION_FAILED` para `yt-dlp` quando bloqueado.
- **Instrumentação & Diagnóstico (`PerformanceTracker.kt`, `DevwolfDownloadService.kt`)**:
  - `PerformanceTracker` e `DevwolfDownloadService` agora registram todas as tentativas por estratégia (`strategyAttempts`) e `httpStatus` (sanitizando tokens/cookies/query strings) no relatório de diagnóstico (`sanitizedLog` e `metrics`).
- **Build & Versionamento (`android/app/build.gradle`)**:
  - Atualizado para `versionCode 30002` e `versionName "3.0.0-rc.2"` mantendo `applicationId "com.devwolf.downloader"` e assinatura compatível para atualização direta via `adb install -r`.

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
