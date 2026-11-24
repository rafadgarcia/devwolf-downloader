# AGENTS.md — Devwolf Downloader (`com.devwolf.downloader`)

Normas permanentes de desenvolvimento, propriedade de arquivos, contratos entre componentes e invariantes de segurança para a **Fase 3 (`v3.0.0`)** — App Android 100% local.
*(A arquitetura híbrida anterior com VPS/Docker/Cobalt permanece preservada na branch `archive/vps-hybrid` e tag `v2.0.0`.)*

## 1. Regras Gerais e Idioma
- Nome oficial da aplicação: **Devwolf Downloader** (`com.devwolf.downloader`).
- Interface do usuário e documentação principal: **Português do Brasil (`pt-BR`)**.
- Execução 100% local no dispositivo Android: zero dependência de VPS, login obrigatório de servidor ou backend remoto.
- Não implementar contorno de DRM, resolução automática de CAPTCHA, captura de senha ou acesso automatizado não autorizado.
- Nunca versionar `docs/Rposty.apk`, `docs/rev-eng/work/` ou `docs/rev-eng/sources/` (protegidos via `.gitignore`).

## 2. Matriz de Propriedade de Arquivos (Fase 3)
- **`@instagram-engine`**:
  - `android/app/src/main/java/com/devwolf/downloader/resolvers/**` (`ResolverContract.kt`, `SafeHttpClient.kt`, `InstagramParsers.kt`, `InstagramNativeResolver.kt`, `HttpMediaDownloader.kt`, `GalleryDlLocalResolver.kt`, `EngineRouter.kt`)
  - `android/app/src/main/java/com/devwolf/downloader/UrlExtractor.kt`
  - `android/app/src/main/java/com/devwolf/downloader/SecureVault.kt`
  - `android/app/src/main/java/com/devwolf/downloader/InstagramSessionActivity.kt`
  - `android/app/src/test/java/com/devwolf/downloader/resolvers/**`, `UrlExtractorTest.kt`
  - `docs/DNR-ARCHITECTURE.md`
- **`@android-performance`**:
  - `android/app/build.gradle`, `android/build.gradle`, `android/gradle.properties`, `android/app/proguard-rules.pro`
  - `android/app/src/main/AndroidManifest.xml`
  - `DevwolfDownloadService.kt`, `DevwolfNativePlugin.kt`, `LocalEngineManager.kt`, `LocalQueueStore.kt`, `MediaStoreHelper.kt`, `StatusModeProcessor.kt`, `PerformanceTracker.kt`, `AppUpdateManager.kt`
  - `StatusModeProcessorTest.kt`, `PerformanceAndErrorContractTest.kt`, `AppUpdateManagerTest.kt`
  - `.github/workflows/ci.yml`, `AGENTS.md`, `TASKS.md`, `DECISIONS.md`, `CHANGELOG.md`, `README.md`, `graphify-out/**`
- **`@frontend-integration`**:
  - `frontend/src/**`, `frontend/public/**`, `frontend/index.html`, `frontend/package.json`

## 3. Contratos Entre Motores, Bridge Nativo (`DevwolfNative`) e UI

### Roteamento de Motores (`EngineRouter`)
- **Instagram** (`/reel/`, `/reels/`, `/p/`, `/tv/`, `/stories/`, `/share/`): `DNR` primário (`EmbedCaptionedStrategy` -> `WebPageJsonStrategy` -> `AuthorizedWebApiStrategy`) -> fallback `yt-dlp` somente em erros recuperáveis.
- **Pinterest** / **Reddit**: resolvedor local Kotlin (`GalleryDlLocalResolver`) -> fallback `yt-dlp` (ex.: DASH `v.redd.it`).
- **YouTube / Shorts**, **TikTok**, **Facebook**, **Twitter/X**: `yt-dlp` em passo único (`--no-simulate --no-warnings --print before_dl:...`) com fallback para resolvedor local de imagens no Twitter/X.
- **Threads**: `ThreadsNativeResolver` (DNR) -> fallback `yt-dlp` somente em erros recuperáveis.
- **Inicialização preguiçosa (`lazy`)**: `YoutubeDL`/Python e `FFmpeg` nunca são inicializados em jobs resolvidos pelo DNR ou resolvedores locais (exceto `FFmpeg` sob demanda se Modo Status estiver ativo para vídeo).
- A tabela `PLATFORM_ROUTING_TABLE` (`frontend/src/services/hybridOrchestrator.ts`) deve espelhar exatamente `EngineRouter.CAPABILITY_TABLE`.
- **Concorrência**: no máximo `DevwolfDownloadService.MAX_CONCURRENT_DOWNLOADS` (2) jobs executam ao mesmo tempo; os demais ficam `queued` e contam como downloads ativos (bloqueiam atualização do motor e parada do serviço).

### Taxonomia Estruturada de Erros (`DevwolfErrorCode`)
- **Terminais** (interrompem imediatamente, sem retry/fallback):
  - `AUTH_REQUIRED`, `PRIVATE_MEDIA`, `RATE_LIMITED`, `CAPTCHA_REQUIRED`, `MEDIA_UNAVAILABLE`, `UNSUPPORTED_MEDIA`, `CANCELLED`, `STORAGE_ERROR`.
- **Recuperáveis** (permitem fallback se houver motor secundário):
  - `EXTRACTOR_OUTDATED`, `EXTRACTION_FAILED`, `NETWORK_ERROR`.
- **Exceção documentada**: `UNSUPPORTED_MEDIA` permite fallback apenas entre `yt-dlp` e o resolvedor local (`GALLERY_DL_LOCAL`), em ambos os sentidos — ex.: tweet só com imagens (`No video could be found`) ou Pin cujo vídeo HLS exige `yt-dlp`.
- Avisos não fatais do `yt-dlp` (como `WARNING: Your yt-dlp version ... is older than 90 days`) e falhas genéricas de parser nunca podem ser classificados como `PRIVATE_MEDIA`.
- Códigos HTTP na saída do `yt-dlp` só são reconhecidos com contexto explícito (`HTTP Error 429`, `status code 404`, ...): IDs numéricos de mídia nunca disparam classificação terminal. Falta de `ffmpeg`/`ffprobe` é `EXTRACTION_FAILED` (falha local), nunca `MEDIA_UNAVAILABLE`.
- Mensagens ao usuário são definidas por `frontend/src/utils/errorContract.ts`; os textos em `DevwolfErrorCode.defaultMessagePtBr` devem apontar para as mesmas telas (ex.: atualização do motor na aba **Motores**).

### Carrosséis Individuais & MediaStore
- Carrosséis são sempre representados item a item (`index` 1..N, `type`: `video` | `image` | `audio`, `fileName`, `mimeType`, `width`, `height`, `sizeBytes`, `bytesDownloaded`, `percent`, `status`, `mediaUri`, `displayPath`).
- Fotos vão para `MediaStore.Images.Media` (`Pictures/Devwolf Downloader`), vídeos para `MediaStore.Video.Media` (`Movies/Devwolf Downloader`) e áudios para `MediaStore.Audio.Media` (`Music/Devwolf Downloader`).
- Arquivos `.zip` ou MIME desconhecidos são rejeitados pelo `MediaStoreHelper` e nunca registrados como vídeo.

### Bridge `DevwolfNative` e Telemetria Local
- Métodos principais: `readClipboard`, `getPendingShareIntent`, `clearPendingShareIntent`, `returnToSourceApp`, `startLocalDownload`, `cancelLocalDownload`, `getLocalDownloadQueue`, `clearLocalDownloadHistory`, `checkMediaExists`, `saveToGallery`, `shareMedia`, `openMedia`, `deleteMedia`, `getLocalEngineStatus`, `updateLocalEngine`, `rollbackLocalEngine`, `getDiagnosticsReport`, `openInstagramLoginWebView`, `openPlatformLoginWebView`, `getPlatformSessions`, `savePlatformSession`, `revokePlatformSession`, `clearTempCache`, `getAppUpdateState`, `checkAppUpdate`, `downloadAppUpdate`, `cancelAppUpdateDownload`, `deleteDownloadedAppUpdate`, `installAppUpdate` (evento `appUpdateProgress`).
- Evento `localDownloadProgress`: emitido no máximo a cada 250 ms por job durante o download (`PROGRESS_EMIT_INTERVAL_MS`) e sempre em mudanças de status. A fila nativa (`LocalQueueStore`) é a fonte de verdade; o `localStorage` do frontend é apenas cache.
- Um job em estado final (`ready`, `failed`, `cancelled`) nunca é sobrescrito por um cancelamento tardio.
- `PerformanceTracker` registra apenas métricas locais (`appOpenMs`, `clipboardDetectMs`, `engineInitMs`, `resolutionMs`, `ttfbMs`, `transferMs`, `processingMs`, `galleryPublishMs`, `totalJobMs`, `httpRequestCount`, `engineUsed`, `strategyUsed`) e sanitiza logs antes de expor `sanitizedLog`.

## 4. Invariantes de Segurança
- **SSRF**: validar `http`/`https`, proibir credenciais embutidas, IPs literais (IPv4/IPv6/hex/octal/inteiro), loopback, RFC1918, CGNAT, link-local (`169.254.0.0/16`), faixas de documentação/benchmark, IPv6 ULA e IPv4 embutido em IPv6 (mapeado, NAT64 `64:ff9b::/96`, 6to4 `2002::/16`), e validar DNS público em cada salto de redirect (máx. 5 saltos). Limitação conhecida: requisições internas do `yt-dlp` não passam por essa validação.
- **Somente HTTPS**: `usesCleartextTraffic="false"` + `network_security_config.xml`; o `SafeHttpClient` promove `http://` para `https://` antes de cada salto.
- **Componentes exportados**: apenas `MainActivity` (launcher + `ACTION_SEND`). `InstagramSessionActivity` não é exportada.
- **Assinatura**: release só é assinado com keystore própria (`RELEASE_STORE_FILE` ...) ou com opt-in explícito da chave de debug (`DEVWOLF_ALLOW_DEBUG_SIGNING=true` / `-PallowDebugSigning=true`); caso contrário o build de release falha.
- **Sessão e Cookies**: cookies armazenados apenas em `EncryptedSharedPreferences` + Android Keystore; enviados estritamente ao host de origem (nunca a CDNs terceiros ou logs); logout via WebView apaga `CookieManager`, `WebStorage` e cofre.
- **Execução segura**: subprocessos (`ffmpeg`/`ffprobe`) sempre via lista de argumentos (`ProcessBuilder`, sem shell); arquivos temporários isolados em `cacheDir/jobs/<jobId>` e removidos no `finally`.
- **Atualização do app (`AppUpdateManager`)**: consulta apenas os releases públicos de `rafadgarcia/devwolf-downloader` (sem token), baixa via `SafeHttpClient` para `filesDir/updates/`, confere o SHA-256 publicado (`digest`) e só entrega ao instalador do sistema um APK com o mesmo `packageName` e o mesmo certificado de assinatura do app instalado. O APK baixado é mantido até ser instalado (ou ficar obsoleto) para nunca exigir novo download. Assets de release devem seguir `devwolf-downloader-<tag>-<abi>-release.apk` (+ `-universal-`).
