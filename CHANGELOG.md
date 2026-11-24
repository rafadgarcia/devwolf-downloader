# Changelog — Devwolf Downloader

Todas as mudanças notáveis do projeto são registradas neste arquivo.

## [3.0.0-rc.9] — Correções na Bolha, Status e Pinterest

### Corrigido
- Bolha do clipboard agora traz o Devwolf para frente e processa o link sem minimizar a própria tela.
- Importador de Status mantém e recupera a pasta SAF vinculada, lista os arquivos ao reabrir Configurações e informa a pasta de destino; botões distinguem **Baixar vídeo** de **Salvar foto**.
- Pins de vídeo do Pinterest agora reconhecem respostas Relay e URLs `v1.pinimg.com` em MP4; sinais de vídeo sem stream direto não podem concluir salvando apenas a capa e seguem para o yt-dlp.

## [3.0.0-rc.8] — Atalho para a Pasta Padrão de Status

### Alterado
- Importação de Status do WhatsApp agora oferece **Padrão (WhatsApp)**, que sugere ao seletor SAF `Android/media/com.whatsapp/WhatsApp/Media/.Statuses`, e **Escolher pasta** para selecionar outro local manualmente. A autorização continua sendo confirmada pelo usuário no seletor do Android.

## [3.0.0-rc.7] — Atualizações no App, Ações Rápidas e Revisão de Segurança

### Adicionado
- **Bolha flutuante de área de transferência e Status do WhatsApp**: atalho opcional na borda da tela; a área de transferência só é lida quando o usuário toca na bolha. Importação de fotos e vídeos via seletor SAF, com acesso persistente à pasta `Statuses` e cópia para a Galeria. Detalhes em `docs/ANDROID-QUICK-ACTIONS.md`.
- **Ajustes → Atualizações (`AppUpdateManager.kt`, `AppUpdatesSection.tsx`, `DevwolfNativePlugin.kt`, `nativeBridge.ts`)**:
  - Compara a versão instalada com o release mais recente do GitHub (SemVer), com a opção **Incluir versões de teste** (pre-release, ligada por padrão) e verificação automática ao abrir a seção (no máximo a cada 6 h).
  - **Baixar e instalar**: baixa o APK da ABI do aparelho (fallback universal) com progresso e cancelamento, confere SHA-256, pacote e assinatura e abre o instalador do Android.
  - Se a permissão **Instalar apps desconhecidos** ainda não foi concedida, abre a tela de permissão e retoma a instalação ao voltar ao app; o APK baixado fica guardado para **Instalar** de novo sem baixar outra vez (ou **Excluir APK**). APKs já instalados/obsoletos são removidos ao abrir o app.
  - Permissão `REQUEST_INSTALL_PACKAGES` no `AndroidManifest.xml`.

### Corrigido
- **Classificação de erros do `yt-dlp` (`ResolverContract.kt`)**: `429`/`404`/`410` só são reconhecidos com contexto (`HTTP Error 429`, `status code 404`...), evitando que IDs numéricos (ex.: TikTok `7340429…`) virem `RATE_LIMITED`/`MEDIA_UNAVAILABLE` terminais; `ffmpeg`/`ffprobe not found` agora é `EXTRACTION_FAILED` (falha local); padrões de rede mais precisos; `SecurityException` do guarda SSRF vira `UNSUPPORTED_MEDIA`.
- **Cancelamento (`DevwolfDownloadService.kt`, `LocalQueueStore.kt`)**: cancelar não sobrescreve mais jobs já `ready`/`failed` nem duplica snapshots; jobs cancelados enquanto aguardam na fila não iniciam; `startService` de cancelamento não quebra em segundo plano (Android 8+).
- **Jobs em execução marcados como falhos**: `markInterruptedJobsOnStartup` ignora jobs ainda ativos no processo quando a Activity/WebView é recriada.
- **Progresso que voltava a 0%** em downloads `bestvideo+bestaudio`: progresso agregado e monotônico entre streams.
- **Limite de 500 MB**: o `yt-dlp` pulava o arquivo silenciosamente; agora o job falha com mensagem explícita.
- **Cookies do X**: arquivo de cookies do `yt-dlp` cobre `.x.com` e `.twitter.com`.

### Performance
- `LocalQueueStore` mantém a fila em memória (compartilhada entre serviço e plugin) e só grava em disco em mudanças de status ou a cada 1,5 s — antes regravava até ~1 MB (com miniaturas) a cada linha de progresso.
- Eventos de progresso do `yt-dlp` limitados a 1 a cada 250 ms; gravação no `localStorage` do frontend limitada a 1 por segundo por job.
- No máximo 2 downloads simultâneos (`MAX_CONCURRENT_DOWNLOADS`); os demais aparecem como **Na fila…**.

### Segurança
- HTTPS-only: `usesCleartextTraffic="false"` + `network_security_config.xml`; `SafeHttpClient` promove `http://` para `https://`.
- `InstagramSessionActivity` deixou de ser exportada (removido deep link `devwolf://oauth-callback`, sem uso desde a rc.6).
- Release só é assinado com keystore própria ou com opt-in explícito da chave de debug (`DEVWOLF_ALLOW_DEBUG_SIGNING=true` / `-PallowDebugSigning=true`).
- SSRF: corrigidas faixas `192.0.0.0/24`, `192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24` (antes bloqueava /16 inteiros ou deixava passar) e bloqueio de IPv4 embutido em IPv6 (NAT64, 6to4, IPv4-compatível, Teredo).
- `SafeHttpClient.sanitizeForDiagnostics` remove o header `Cookie` inteiro e tokens do Reddit/Instagram (`token_v2`, `reddit_session`, `ds_user_id`...).

### Alterado
- Frontend alinhado ao `EngineRouter`: Instagram usa fallback só `yt-dlp`; plataforma desconhecida não se apresenta mais como Instagram; mensagens de `EXTRACTOR_OUTDATED`/`AUTH_REQUIRED` iguais no Kotlin e no TypeScript.
- Documentação: `AGENTS.md` atualizado (métodos da bridge, exceção de fallback em `UNSUPPORTED_MEDIA`, invariantes novos); `docs/maintenance.md` e `docs/troubleshooting.md` reescritos para o app Android; removidos `docs/deployment.md`, `frontend/Dockerfile` (preservados em `archive/vps-hybrid`) e testes de exemplo do Capacitor.

## [3.0.0-rc.6] — Deduplicação com Verificação na Galeria, Limpeza de Histórico, Login Direto Reddit/SPA & Ícones SVG

### Adicionado
- **Deduplicação com verificação real na Galeria (`MediaStoreHelper.kt`, `DevwolfNativePlugin.kt`, `localQueue.ts`, `nativeBridge.ts`, `App.tsx`)**:
  - Adicionado `MediaStoreHelper.doesMediaExist` (verificando `MediaStore` via `ContentResolver.query` / `openAssetFileDescriptor` para URIs `content://` e existência/tamanho para caminhos locais) e método `DevwolfNative.checkMediaExists({ uris, filePaths })`.
  - Ao colar ou adicionar um link já concluído, o app verifica se a mídia ainda existe na Galeria: se existir, bloqueia a duplicata com aviso e destaca o card existente (permitindo ainda baixar novamente pelo botão **Repetir** com `allowDuplicate: true`).
- **Limpeza de Histórico (`LocalQueueStore.kt`, `DevwolfNativePlugin.kt`, `HistoryView.tsx`, `localQueue.ts`, `dedupe.ts`, `App.tsx`)**:
  - Adicionado `LocalQueueStore.clearHistory(keepActiveJobs = true)` e `DevwolfNative.clearLocalDownloadHistory()` removendo jobs concluídos/falhos/cancelados da fila persistida sem apagar arquivos salvos na Galeria.
  - Botão **Limpar histórico** no cabeçalho de **Baixados** (`HistoryView.tsx`) limpando histórico e cache de deduplicação.
- **Ícones SVG vetoriais por plataforma (`PlatformIcon.tsx`, `HomeView.tsx`, `MediaThumbnail.tsx`, `HistoryView.tsx`)**:
  - Substituídos rótulos curtos de texto (`IG`, `TT`, `RD`, etc.) no Hero card, grade **Baixado recentemente**, fallback de thumbnails e badges do histórico por ícones SVG vetoriais dedicados.

### Corrigido & Alterado
- **Captura de sessão Reddit/Instagram/Threads em SPAs & remoção do botão "Navegador" (`InstagramSessionActivity.kt`, `DevwolfDownloadService.kt`, `GalleryDlLocalResolver.kt`, `SettingsView.tsx`)**:
  - Removido o botão **Navegador** de `InstagramSessionActivity.kt` (que abria Chrome externo sem poder retornar cookies ao app), tornando o navegador integrado com tema **Purple Glass** (`#0D0B1E` / `#14112E` / `#7C5CFF`) a tela única e principal de login.
  - Interceptação de navegação SPA via `doUpdateVisitedHistory` + watcher periódico a cada `1200ms` e detecção precisa de login autenticado no Reddit via `isAuthenticatedRedditCookie` (`reddit_session` ou JWT `token_v2` com `sub: "t2_..."`, ignorando `token_v2` anônimo).
  - `DevwolfDownloadService.resolveYtdlpUserAgent` agora alinha `--user-agent` com o `User-Agent` salvo da sessão WebView também para `reddit` junto com `scoped.cookies.txt`.
  - Simplificado o card **Contas e Sessões** em `SettingsView.tsx` (removida caixa técnica de OAuth2/`localhost`/Client ID do Reddit, botão único `Conectar <Plataforma>`) e removido rodapé redundante das Configurações.
- **Build & Versionamento (`android/app/build.gradle`, `frontend/package.json`)**:
  - Atualizado para `versionCode 30006` e `versionName "3.0.0-rc.6"`.

## [3.0.0-rc.5] — Correção R8 `yt-dlp`, FFmpeg/Downloads, Navegação Botão Voltar, Polimento UI & OAuth no Navegador

### Corrigido
- **Crash R8/Release ao atualizar `yt-dlp` e leitura de zipapp (`proguard-rules.pro`, `LocalEngineManager.kt`, `DevwolfNativePlugin.kt`)**:
  - Adicionadas regras R8 (`-keep`) para `com.fasterxml.jackson.**`, `@com.fasterxml.jackson.annotation.**`, `org.apache.commons.**`, `org.tukaani.xz.**`, `kotlin.Metadata`, `kotlin.**` e `org.chromium.**` evitando a remoção de classes/anotações usadas por `YoutubeDLUpdater` no APK `release` (`minifyEnabled true`).
  - `LocalEngineManager.readBinaryVersionFromZipapp` substituído por `ZipInputStream` com salto de preâmbulo shebang (`#!/usr/bin/env python3`) até a assinatura `PK\u0003\u0004`, evitando falhas de `ZipFile` no ART.
  - Sincronização de `updateEngine`, `rollbackEngine` e `maybeAutoUpdateInBackground` via `updateLock` + atraso inicial de 15s no startup (`DevwolfNativePlugin.load()`), além de validação resiliente via `YoutubeDL.getInstance().version(appCtx)` sem falsos rollbacks em `ALREADY_UP_TO_DATE`.
- **Downloads travando em `"Resolvendo publicação..."`, título `"null"` e inicialização de FFmpeg (`DevwolfDownloadService.kt`, `InstagramParsers.kt`, `ThreadsNativeResolver.kt`, `localQueue.ts`, `HistoryView.tsx`)**:
  - Motor inicial determinado por plataforma via `initialEngineForPlatform` (`yt-dlp` para YouTube/TikTok/Facebook/Twitter, `dnr` para Instagram/Threads, `gallery-dl` para Pinterest/Reddit) antes do primeiro snapshot, eliminando exibição falsa de `dnr` no YouTube.
  - Chamada de `LocalEngineManager.ensureInitialized(applicationContext)` (inicializando `YoutubeDL` e `FFmpeg`) antes de `executeSinglePassYtdlp`, garantindo extração das bibliotecas do `ffmpeg` para mesclagem `bestvideo+bestaudio` e `--merge-output-format mp4`.
  - Emissão imediata de progresso quando `DEVWOLF_META:` / `[Merger]` / callbacks do `yt-dlp` chegam e template seguro `-o media_%(id)s_%(autonumber)s.%(ext)s`.
  - Substituídos `optString` inseguros por `optCleanString` / `optNonNullString` (Kotlin) e `cleanNullableString` (frontend) impedindo que `JSONObject.NULL` vire título literal `"null"`.

### Adicionado & Alterado
- **Botão Voltar do Android (`MainActivity.kt`, `App.tsx`)**:
  - `OnBackPressedCallback` consulta `window.__devwolfHandleBack()` / `devwolf:hardwareBack`: fecha modais abertos, retorna abas secundárias (`history`, `engines`, `settings`) para **Início** (`home`) e só fecha/minimiza o app quando já estiver em **Início**.
- **Navegador do Dispositivo para Reddit OAuth2 & Sessões (`InstagramSessionActivity.kt`, `AndroidManifest.xml`, `SettingsView.tsx`)**:
  - Suporte a callback `devwolf://oauth-callback` (`launchMode="singleTop"`) e botão **Navegador** em `InstagramSessionActivity`: permite autorizar Reddit OAuth2 PKCE no navegador padrão onde o usuário já está logado e explica a separação de cookies Chrome vs WebView persistente no Instagram/Threads.
- **Polimento da Interface (`Header.tsx`, `AddLinkModal.tsx`, `HistoryView.tsx`, `EnginesView.tsx`, `hybridOrchestrator.ts`)**:
  - Removido `+` duplicado do cabeçalho (mantido apenas `⋮` e FAB `+` na Home); removido `?` do modal **Adicionar link**; botão `✕` em círculo perfeito centralizado à direita; badge `<plataforma> detectado` alinhado em `40px` com os selects `Auto`/`Melhor`.
  - Em **Baixados**: botão **Repetir** somente com ícone e remoção de **Editar URL**.
  - Em **Motores**: removido aviso de rodapé e promovido `yt-dlp + FFmpeg` como primeiro card / motor universal para YouTube, TikTok, Facebook e X/Twitter.
- **Build & Versionamento (`android/app/build.gradle`, `frontend/package.json`)**:
  - Atualizado para `versionCode 30005` e `versionName "3.0.0-rc.5"`.

## [3.0.0-rc.4] — Redesign Rposty, Tema Roxo Glassmorphism, Thumbnails Nativas & Barra Roxa

### Adicionado
- **Thumbnails Nativas para Hero e "Baixado recentemente" (`MediaStoreHelper.kt`, `DevwolfDownloadService.kt`, `LocalQueueStore.kt`)**:
  - `MediaStoreHelper.generateThumbnailDataUrl` gera miniaturas JPEG compactas (`480px` máx. mantendo proporção, `quality = 75`, `data:image/jpeg;base64,...`) via `MediaMetadataRetriever` (vídeos) e `BitmapFactory` com `inSampleSize` (imagens), diretamente do arquivo antes da limpeza temporária — sem exigir permissões extras de leitura no WebView.
  - `thumbnailDataUrl` anexado a cada item em `files[]`/`items[]` e no snapshot do job, persistido no `LocalQueueStore` para os 24 itens mais recentes e compactado no `localStorage` do frontend acima de 12 itens.
- **Redesign Home estilo Rposty & Sistema de Design Roxo/Índigo Glassmorphism (`frontend/src/`)**:
  - Paleta `#0D0B1E` -> `#14112E` com cards glassmórficos (`#1E1940`), acentos `#7C5CFF` / `#5E43F3` e destaques `#A78BFA` / `#C4B5FD` (`manifest.webmanifest`, `tailwind.config.js`, `styles/index.css`).
  - Novo **Hero Thumbnail Card** no topo da Home (com preview real, play central, badge de mídia, barra inferior glass com plataforma/título/ações rápidas e progresso ao vivo durante downloads) e grade **Baixado recentemente** (`grid-cols-3 sm:grid-cols-4`, com seta `→` para o Histórico), removendo a seção "Visitados frequentemente".
  - Botão flutuante **`+`** com modal **Adicionar link de download** (`AddLinkModal.tsx`) e modal **Como baixar?** (`HowToDownloadModal.tsx`) usando `<dialog>` nativo + `closedby="any"`.
  - Cabeçalho limpo (`Header.tsx`) com menu **`⋮`** (`popover="auto"` + `focusgroup="menu nomemory"`), removendo a barra de navegação inferior.

### Alterado
- **Tema Nativo Android Sem Flash Branco (`colors.xml`, `styles.xml`, `MainActivity.kt`)**:
  - Adicionado `colors.xml` e atualizados `AppTheme`, `AppTheme.NoActionBar`, `AppTheme.NoActionBarLaunch` (`Theme.SplashScreen`) e `MainActivity.applyPurpleSystemChrome` com `#0D0B1E` na splash, janela, WebView, barra de status e barra de navegação.
- **Organização de Ajustes & Nomenclatura Limpa (`DevwolfDownloadService.kt`, `SettingsView.tsx`, `EnginesView.tsx`)**:
  - **Modo Status (WhatsApp)** e seletor de **Tema da interface** (`Escuro Roxo` / `Claro`) movidos exclusivamente para **Configurações (Ajustes)**.
  - Removidos rótulos redundantes `"Local"` nos motores (`DNR • Instagram`, `DNR • Threads`, `yt-dlp`, `gallery-dl`, `Status dos Motores`) e padronizados rótulos nativos de fase em pt-BR (`Resolvendo publicação...`, `Baixando mídia...`, `Processando mídia...`, `Salvo na galeria`).
- **Build & Versionamento (`android/app/build.gradle`, `frontend/package.json`)**:
  - Atualizado para `versionCode 30004` e `versionName "3.0.0-rc.4"` mantendo `applicationId "com.devwolf.downloader"` e assinatura compatível.

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
