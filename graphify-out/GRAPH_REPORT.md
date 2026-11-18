# Graph Report - Devwolf Downloader  (2026-11-18)

## Corpus Check
- 108 files · ~95,115 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 25 file(s) not represented in the graph (top: .xml 12, (none) 5, .properties 2)

## Summary
- 1387 nodes · 3113 edges · 78 communities (51 shown, 27 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 244 edges (avg confidence: 0.92)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `0959ba16`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- InstagramParsers
- Fluxo reconstruído
- package.json
- InstagramSessionActivity.kt
- InstagramNativeResolver
- ResolverException
- hybridOrchestrator.ts
- EngineRouter
- evidence/package.json
- verificar-erros.cjs
- DropLink — Downloader universal self-hosted
- MediaStoreHelper.kt
- url.ts
- ResolvedMediaItem
- LocalEngineManager
- App.tsx
- compilerOptions
- api.ts
- TASKS.md — DropLink
- normalized_result
- with
- .resolveReddit
- HomeView.tsx
- Auditoria Diagnóstica `v3.0.0-rc.1` — Instagram (`CAPTCHA_REQUIRED`) e Reddit (`PRIVATE_MEDIA`)
- localQueue.ts
- Dispatcher
- .redditMobileShareLinksSendHtmlAcceptExtractCanonicalAndNeverAppendJsonToSharePath
- subject
- engine
- loaded
- raw_result
- compilerOptions
- components
- manifest
- .processForStatusMode
- provider
- SecureVault
- Rc3EnginesRegressionTest.kt
- Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais
- UrlExtractor.kt
- PerformanceAndErrorContractTest
- Changelog — Devwolf Downloader
- DevwolfDownloadService
- Guia de Deploy na VPS — DropLink
- sw.js
- UrlExtractor
- .executeSinglePassYtdlp
- .fromYtdlpOutput
- .tryCaptureSession
- DevwolfErrorCode
- GalleryDlLocalResolver.kt
- Guia de Manutenção, Atualizações e Backup — DropLink
- .detectPlatform
- DECISIONS.md — Decisões Arquiteturais
- 2. Problemas Comuns e Soluções
- DevwolfDownloadService.kt
- .prepareDownloadedNativeItems
- ThreadsNativeResolver
- DevwolfNativePlugin
- Rc3EnginesRegressionTest.kt
- LocalQueueStore
- SafeHttpClient

## God Nodes (most connected - your core abstractions)
1. `DevwolfDownloadService` - 54 edges
2. `GalleryDlLocalResolver` - 47 edges
3. `SecureVault` - 44 edges
4. `SafeHttpClient` - 35 edges
5. `DevwolfNativePlugin` - 34 edges
6. `App()` - 32 edges
7. `LocalEngineManager` - 31 edges
8. `InstagramSessionActivity` - 30 edges
9. `ResolvedMediaItem` - 27 edges
10. `ResolverException` - 27 edges

## Surprising Connections (you probably didn't know these)
- `Adicionado` --references--> `DevwolfDownloadService`  [INFERRED]
  CHANGELOG.md → android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt
- `Adicionado & Alterado` --references--> `InstagramSessionActivity`  [INFERRED]
  CHANGELOG.md → android/app/src/main/java/com/devwolf/downloader/InstagramSessionActivity.kt
- `4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`)` --references--> `LocalEngineManager`  [INFERRED]
  docs/diagnostics/rc1/reddit-audit.md → android/app/src/main/java/com/devwolf/downloader/LocalEngineManager.kt
- `1. Entrada e seleção do post` --references--> `MainActivity`  [INFERRED]
  docs/rev-eng/RELATORIO.md → android/app/src/main/java/com/devwolf/downloader/MainActivity.kt
- `Carrosséis Individuais & MediaStore` --references--> `MediaStoreHelper`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/MediaStoreHelper.kt

## Import Cycles
- None detected.

## Communities (78 total, 27 thin omitted)

### Community 0 - "InstagramParsers"
Cohesion: 0.16
Nodes (3): InstagramParsedMedia, InstagramParsers, InstagramParsersAndErrorTest

### Community 1 - "Fluxo reconstruído"
Cohesion: 0.07
Nodes (25): Evidência auxiliar e alcance, Fontes auxiliares selecionadas, Relação achado → evidência, Índice de evidências, Ferramentas, Metodologia e reprodução, O que não foi feito, Repetir a inspeção (+17 more)

### Community 2 - "package.json"
Cohesion: 0.06
Nodes (38): config, dependencies, @capacitor/android@^8.6.2, @capacitor/cli@^8.6.2, @capacitor/core@^8.6.2, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1 (+30 more)

### Community 3 - "InstagramSessionActivity.kt"
Cohesion: 0.06
Nodes (4): InstagramSessionActivity, OnBackPressedCallback, WebChromeClient, WebViewClient

### Community 4 - "InstagramNativeResolver"
Cohesion: 0.16
Nodes (6): InstagramNativeResolver, InstagramPageContext, MediaResolver, ResolutionRequest, DnrAndLocalResolversIntegrationTest, Etapa 8 — v3.0.0-rc.2: Correções Instagram (DNR/CAPTCHA) & Reddit (403/Share Links)

### Community 6 - "ResolverException"
Cohesion: 0.24
Nodes (5): ResolutionMetrics, ResolutionResult, ResolverException, StrategyAttemptMetric, Módulos principais (`com.devwolf.downloader.resolvers`)

### Community 7 - "hybridOrchestrator.ts"
Cohesion: 0.29
Nodes (12): ALL_11_CODES, DevwolfErrorCode, DEVWOLF_ERROR_CONTRACT, ErrorContractEntry, getAuthRequiredMessageForPlatform(), getErrorContract(), getUserFacingErrorMessage(), isTerminalPlatformError() (+4 more)

### Community 8 - "EngineRouter"
Cohesion: 0.15
Nodes (12): DnrTelemetrySnapshot, EngineRoutePlan, EngineRouter, PlatformRouteCapability, EngineKind, DNR, GALLERY_DL_LOCAL, YT_DLP (+4 more)

### Community 9 - "evidence/package.json"
Cohesion: 0.13
Nodes (14): analysis_profile, authority, caller_input_path, confidence, environment, evidence_id, evidence_links, limitations (+6 more)

### Community 10 - "verificar-erros.cjs"
Cohesion: 0.13
Nodes (9): cases, matches, moduleObject, native, projectRequire, root, sandbox, source (+1 more)

### Community 11 - "DropLink — Downloader universal self-hosted"
Cohesion: 0.06
Nodes (32): 0. Instruções para o agente de desenvolvimento, 10. Segurança, privacidade e limites, 11. Estrutura de repositório sugerida, 12. Plano de implementação e entregáveis, 13. Critérios de aceitação, 14. Referências oficiais para implementação, 15. Instrução inicial para execução no Antigravity, 1. Visão do produto (+24 more)

### Community 12 - "MediaStoreHelper.kt"
Cohesion: 0.08
Nodes (6): MediaStoreHelper, SavedMediaResult, NotificationActionReceiver, [3.0.0-rc.4] — Redesign Rposty, Tema Roxo Glassmorphism, Thumbnails Nativas & Barra Roxa, Adicionado, Etapa 12 — v3.0.0-rc.6: Deduplicação com Galeria, Limpeza de Histórico, Login Direto SPA & Ícones SVG

### Community 13 - "url.ts"
Cohesion: 0.11
Nodes (30): vitest@^2.1.8, AddLinkModal(), HowToDownloadModal(), HowToDownloadModalProps, MultiUrlModal(), MultiUrlModalProps, ClipboardPermissionStatus, UseClipboardAutoDetectOptions (+22 more)

### Community 14 - "ResolvedMediaItem"
Cohesion: 0.25
Nodes (8): HttpMediaDownloader, HttpDownloadProgress, HttpDownloadResult, MediaItemType, AUDIO, IMAGE, VIDEO, ResolvedMediaItem

### Community 16 - "App.tsx"
Cohesion: 0.11
Nodes (33): App(), generateIdempotencyKey(), VALID_TABS, Window, Header(), HeaderProps, NavTab, VIEW_TITLES (+25 more)

### Community 17 - "compilerOptions"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleResolution, noEmit (+11 more)

### Community 18 - "api.ts"
Cohesion: 0.13
Nodes (26): AddLinkModalProps, EnginesView(), EnginesViewProps, ACCOUNT_PLATFORMS, ManagedAccountPlatform, SettingsView(), DevwolfNative, LocalDownloadProgressEvent (+18 more)

### Community 19 - "TASKS.md — DropLink"
Cohesion: 0.17
Nodes (11): Etapa 10 — v3.0.0-rc.4: Redesign Rposty, Tema Roxo Glassmorphism & Thumbnails Nativas, Etapa 11 — v3.0.0-rc.5: Estabilização R8/yt-dlp, FFmpeg, Botão Voltar, Polimento UI & Navegador OAuth, Etapa 1 — Fundação, Git e Contratos, Etapa 2 — Backend, Banco de Dados e Motores, Etapa 3 — Frontend, PWA e Experiência Mobile, Etapa 4 — Docker, Scripts de Operação e Documentação, Etapa 5 — Graphify e Verificação Integrada, Etapa 6 — Fase 2: Devwolf Downloader Híbrido (Android + VPS) (+3 more)

### Community 20 - "normalized_result"
Cohesion: 0.20
Nodes (10): normalized_result, class_count, min_sdk, package_name, permissions, resource_count, signature_verification, target_sdk (+2 more)

### Community 21 - "with"
Cohesion: 0.07
Nodes (32): name, needs, runs-on, steps, run, defaults, name, runs-on (+24 more)

### Community 22 - ".resolveReddit"
Cohesion: 0.19
Nodes (10): GalleryDlLocalResolver, Corrigido, 6. Investigação 5: Classificação Incorreta de `PRIVATE_MEDIA` no Reddit, Alterações de Código Implementadas, 2. Causas-Raiz Comprovadas, Causa-Raiz R1 — Mapeamento Incondicional de HTTP `403` para `PRIVATE_MEDIA` (`GalleryDlLocalResolver.kt:155-162`), Causa-Raiz R2 — Cabeçalhos Propensos a Bloqueio Fastly/WAF e Omissão de Cookie (`GalleryDlLocalResolver.kt:125-128`), Causa-Raiz R3 — Falha Silenciosa em Links de Compartilhamento Móvel `/r/<sub>/s/<share_id>` (`SafeHttpClient.kt:180-230`, `GalleryDlLocalResolver.kt:135-145, 801-808`) (+2 more)

### Community 23 - "HomeView.tsx"
Cohesion: 0.10
Nodes (49): HeroActionsModal(), HeroActionsModalProps, HistoryView(), HistoryViewProps, PLATFORM_FILTERS, toCarouselItem(), ACTIVE_STATUSES, fileToCarouselItem() (+41 more)

### Community 24 - "Auditoria Diagnóstica `v3.0.0-rc.1` — Instagram (`CAPTCHA_REQUIRED`) e Reddit (`PRIVATE_MEDIA`)"
Cohesion: 0.13
Nodes (14): 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`, 1. Resumo Executivo dos Resultados Físicos (`v3.0.0-rc.1`), 2. Investigação 1: Falsos Positivos de `CAPTCHA_REQUIRED` no Instagram, 4. Investigação 3: Interrupção Prematura das Estratégias do DNR e Fallback, 5. Investigação 4: Recomparação Detalhada com Evidências do Rposty (REA/JADX), 7. Investigação 6: Issues Recentes Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`), 8. Investigação 7: Testes Reproduzíveis de Diagnóstico (`Rc1DiagnosticAuditTest.kt`), 9. Causas Comprovadas, Hipóteses Restantes e Plano de Correções Recomendadas (+6 more)

### Community 25 - "localQueue.ts"
Cohesion: 0.24
Nodes (23): applyLocalProgressToJob(), cleanNullableString(), createInitialLocalJob(), extractJobMediaLocations(), filterLocalHistory(), findExistingReadyJobByNormalizedUrl(), getDefaultEngineForPlatform(), getLocalJobById() (+15 more)

### Community 27 - "Dispatcher"
Cohesion: 0.20
Nodes (7): Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher

### Community 28 - ".redditMobileShareLinksSendHtmlAcceptExtractCanonicalAndNeverAppendJsonToSharePath"
Cohesion: 0.24
Nodes (6): Rc1DiagnosticAuditTest, 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`, 3. Matriz de Diferenciação de Respostas HTTP do Reddit, 4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`), Auditoria Diagnóstica RC1 — Reddit (`PRIVATE_MEDIA` em Conteúdo Público), Resultados dos Testes (`v3.0.0-rc.2`)

### Community 29 - "subject"
Cohesion: 0.29
Nodes (7): sha256, subject, architecture, digest, format, local_path, name

### Community 30 - "engine"
Cohesion: 0.29
Nodes (7): artifact_sha256, heap_limit_mib, name, source_revision, version, worker_count, engine

### Community 31 - "loaded"
Cohesion: 0.29
Nodes (7): apk_path, class_count, resource_count, resources, state, threads, loaded

### Community 32 - "raw_result"
Cohesion: 0.29
Nodes (7): raw_result, bridge_sha256, calls, jar_sha256, server, name, version

### Community 33 - "compilerOptions"
Cohesion: 0.22
Nodes (8): compilerOptions, allowSyntheticDefaultImports, composite, module, moduleResolution, skipLibCheck, strict, include

### Community 34 - "components"
Cohesion: 0.50
Nodes (4): items, limitations, status, components

### Community 35 - "manifest"
Cohesion: 0.50
Nodes (4): reported_total_bytes, status, text, manifest

### Community 36 - ".processForStatusMode"
Cohesion: 0.17
Nodes (4): MediaProbeInfo, ProcessedMediaItem, StatusModeProcessor, StatusModeProcessorTest

### Community 37 - "provider"
Cohesion: 0.50
Nodes (4): provider, id, name, version

### Community 38 - "SecureVault"
Cohesion: 0.12
Nodes (8): SecureVault, StoredPlatformSession, 3. Suporte Nativo ao Threads (`threads.com` & `threads.net`) e Avaliação de `tribixbite/yt-dlp-threads`, 4. Cofre Multi-Plataforma (`SecureVault.kt`) e Autenticação Oficial do Reddit, Arquitetura Multi-Plataforma (`SecureVault.kt`), Arquitetura Reddit Data API / OAuth2 & Conformidade de Política, Auditoria Técnica e Arquitetura `v3.0.0-rc.3`, Avaliação do `tribixbite/yt-dlp-threads`

### Community 40 - "Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais"
Cohesion: 0.20
Nodes (10): 1. Visão Geral e Separação de Responsabilidades, 2. Contrato de Erros Estruturados (`DevwolfErrorCode`), 3. Estratégias Isoladas do Instagram (DNR), 5. Avaliação do `gallery-dl` Local no Android (`GalleryDlLocalResolver.kt`), 6. Tabela Central de Roteamento (`EngineRouter.kt`), Análise do Runtime Python Embutido (`youtubedl-android:0.18.1`), Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais, Carrosséis Individuais (Sem `.zip`) (+2 more)

### Community 41 - "UrlExtractor.kt"
Cohesion: 0.15
Nodes (8): InstagramUrlKind, POST, REEL, SHARE, STORIES, TV, UNKNOWN, ParsedInstagramUrl

### Community 43 - "Changelog — Devwolf Downloader"
Cohesion: 0.14
Nodes (14): [1.0.0] — Fase 1: Baseline Self-Hosted PWA, [2.0.0] — Fase 2: App Híbrido Android, [3.0.0] — Fase 3: Android 100% Local, DNR Instagram, Carrosséis Individuais & Otimização, [3.0.0-rc.2] — Correções de Diagnóstico RC1 (Instagram & Reddit), [3.0.0-rc.3] — Pinterest Vídeo, TikTok `yt-dlp`, Threads Nativo, Contas Multiplataforma & Diagnóstico Completo, [3.0.0-rc.5] — Correção R8 `yt-dlp`, FFmpeg/Downloads, Navegação Botão Voltar, Polimento UI & OAuth no Navegador, Adicionado, Adicionado (+6 more)

### Community 45 - "Guia de Deploy na VPS — DropLink"
Cohesion: 0.20
Nodes (9): 1. Inventário Read-Only da VPS (Execute Antes de Alterar Qualquer Coisa), 2. Configuração de Ambiente (`.env`), 3. Subindo o DropLink em Paralelo ao Cobalt, 4. Proxy Reverso e HTTPS (Mesma Origem), 5. Instalar o Atualizador Automático do Cobalt (`systemd` no Host), 6. Verificação Pós-Deploy (PWA e Share Target no Android), Exemplo Caddy, Exemplo Nginx (adicionar ao vhost existente) (+1 more)

### Community 49 - ".executeSinglePassYtdlp"
Cohesion: 0.20
Nodes (3): YtdlpExecutionOutcome, YtdlpFailureException, YtdlpMetadata

### Community 50 - ".fromYtdlpOutput"
Cohesion: 0.29
Nodes (4): ErrorClassifier, 2. TikTok: Causa Raiz de `EXTRACTOR_OUTDATED` e Correção (`ResolverContract.kt`, `UrlExtractor.kt`), Causa Raiz Comprovada, Correções Implementadas

### Community 51 - ".tryCaptureSession"
Cohesion: 0.24
Nodes (7): StoredInstagramSession, [3.0.0-rc.6] — Deduplicação com Verificação na Galeria, Limpeza de Histórico, Login Direto Reddit/SPA & Ícones SVG, Adicionado, Corrigido & Alterado, 3. Investigação 2: Uso da Sessão Autenticada do Instagram pelo DNR, Lacunas na Estratégia 3 (`AuthorizedWebApiStrategy`) e Extração de `media_id`, Rastreamento Completo da Cadeia de Sessão

### Community 53 - "DevwolfErrorCode"
Cohesion: 0.17
Nodes (12): DevwolfErrorCode, AUTH_REQUIRED, CANCELLED, CAPTCHA_REQUIRED, EXTRACTION_FAILED, EXTRACTOR_OUTDATED, MEDIA_UNAVAILABLE, NETWORK_ERROR (+4 more)

### Community 58 - "Guia de Manutenção, Atualizações e Backup — DropLink"
Cohesion: 0.22
Nodes (8): 1. Atualização Automática e Segura do Cobalt, 2. Atualização de `yt-dlp` e `gallery-dl` (Worker), 3. Backup e Restauração (SQLite WAL + Configurações), Agendar backup diário via cron (exemplo):, Comandos úteis no host:, Como o fluxo funciona:, Guia de Manutenção, Atualizações e Backup — DropLink, Procedimento de Restauração:

### Community 60 - "DECISIONS.md — Decisões Arquiteturais"
Cohesion: 0.06
Nodes (30): 1. Regras Gerais e Idioma, 2. Matriz de Propriedade de Arquivos (Fase 3), 3. Contratos Entre Motores, Bridge Nativo (`DevwolfNative`) e UI, 4. Invariantes de Segurança, AGENTS.md — Devwolf Downloader (`com.devwolf.downloader`), Bridge `DevwolfNative` e Telemetria Local, Carrosséis Individuais & MediaStore, Roteamento de Motores (`EngineRouter`) (+22 more)

### Community 61 - "2. Problemas Comuns e Soluções"
Cohesion: 0.18
Nodes (10): 1. Limitações Reais de Plataforma (Comportamento Esperado), 2. Problemas Comuns e Soluções, App Android (`com.devwolf.downloader`) — Diagnóstico Híbrido, Cobalt aparece como "Offline" ou "Degradado" na aba Motores, Diagnóstico de Problemas e Limitações Reais — DropLink, Disco cheio ou novos jobs recusados, Erro de login / CSRF inválido após proxy reverso, Progresso não atualiza em tempo real (SSE travado) (+2 more)

### Community 64 - "ThreadsNativeResolver"
Cohesion: 0.14
Nodes (7): ThreadsNativeResolver, ThreadsParsedMedia, ParsedThreadsUrl, ThreadsUrlKind, POST, SHORTLINK, UNKNOWN

### Community 67 - "Rc3EnginesRegressionTest.kt"
Cohesion: 0.11
Nodes (4): InMemorySharedPreferences, Editor, Rc3EnginesRegressionTest, REMOVED

### Community 68 - "LocalQueueStore"
Cohesion: 0.06
Nodes (6): ExampleInstrumentedTest, LocalQueueStore, MainActivity, OnBackPressedCallback, ExampleUnitTest, Alterado

### Community 73 - "SafeHttpClient"
Cohesion: 0.08
Nodes (7): JobPerformanceRecord, PerformanceTracker, HttpTextResponse, SafeHttpClient, EventListener, EventListener, 4. Sessão Opcional via WebView e Cofre Criptografado

## Knowledge Gaps
- **26 isolated node(s):** `REEL`, `POST`, `TV`, `STORIES`, `SHARE` (+21 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 413 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **27 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Corrigido` connect `LocalEngineManager` to `.load`, `Changelog — Devwolf Downloader`, `DevwolfDownloadService`, `.executeSinglePassYtdlp`, `localQueue.ts`?**
  _High betweenness centrality (0.163) - this node is a cross-community bridge._
- **Are the 7 inferred relationships involving `DevwolfDownloadService` (e.g. with `Adicionado` and `Corrigido`) actually correct?**
  _`DevwolfDownloadService` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `REEL`, `POST`, `TV` to the rest of the system?**
  _26 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `Fluxo reconstruído` be split into smaller, more focused modules?**
  _Cohesion score 0.07389162561576355 - nodes in this community are weakly interconnected._
- **Why does `cleanNullableString()` connect `localQueue.ts` to `HomeView.tsx`, `LocalEngineManager`?**
  _High betweenness centrality (0.162) - this node is a cross-community bridge._
- **Are the 12 inferred relationships involving `GalleryDlLocalResolver` (e.g. with `Roteamento de Motores (`EngineRouter`)` and `.redditClassifiesFastly403WafAsRecoverableExtractionFailedAndRealPrivateAsTerminal()`) actually correct?**
  _`GalleryDlLocalResolver` has 12 INFERRED edges - model-reasoned connections that need verification._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.06039488966318235 - nodes in this community are weakly interconnected._