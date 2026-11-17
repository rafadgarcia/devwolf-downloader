# Graph Report - Devwolf Downloader  (2026-11-17)

## Corpus Check
- 97 files · ~65,455 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 23 file(s) not represented in the graph (top: .xml 11, (none) 5, .properties 2)

## Summary
- 1075 nodes · 2224 edges · 60 communities (42 shown, 18 thin omitted)
- Extraction: 96% EXTRACTED · 4% INFERRED · 0% AMBIGUOUS · INFERRED: 96 edges (avg confidence: 0.89)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `f496a2c3`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- InstagramParsers
- MainActivity.kt
- package.json
- InstagramSessionActivity.kt
- SafeHttpClient
- GalleryDlLocalResolver
- .resolve
- hybridOrchestrator.ts
- EngineRouter
- evidence/package.json
- verificar-erros.cjs
- DropLink — Downloader universal self-hosted
- UrlExtractor
- url.ts
- ResolverException
- LocalEngineManager
- App.tsx
- compilerOptions
- api.ts
- Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais
- normalized_result
- with
- LocalEngineManager.kt
- HistoryView.tsx
- App
- localQueue.ts
- .detectPlatform
- DnrAndLocalResolversIntegrationTest.kt
- subject
- engine
- loaded
- raw_result
- compilerOptions
- components
- manifest
- .processForStatusMode
- provider
- DevwolfDownloadService
- Guia de Deploy na VPS — DropLink
- sw.js
- Guia de Manutenção, Atualizações e Backup — DropLink
- DECISIONS.md — Decisões Arquiteturais
- 2. Problemas Comuns e Soluções
- DevwolfNativePlugin
- UrlExtractor.kt
- DevwolfNativePlugin
- LocalQueueStore
- DevwolfErrorCode

## God Nodes (most connected - your core abstractions)
1. `DevwolfDownloadService` - 40 edges
2. `GalleryDlLocalResolver` - 31 edges
3. `DevwolfNativePlugin` - 30 edges
4. `App()` - 29 edges
5. `LocalEngineManager` - 27 edges
6. `SecureVault` - 26 edges
7. `InstagramParsers` - 24 edges
8. `SafeHttpClient` - 24 edges
9. `DevwolfNativePlugin` - 22 edges
10. `ResolvedMediaItem` - 20 edges

## Surprising Connections (you probably didn't know these)
- `Adicionado` --references--> `DevwolfDownloadService`  [INFERRED]
  CHANGELOG.md → android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt
- `Carrosséis Individuais & MediaStore` --references--> `MediaStoreHelper`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/MediaStoreHelper.kt
- `Bridge `DevwolfNative` e Telemetria Local` --references--> `PerformanceTracker`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/PerformanceTracker.kt
- `6. Tabela Central de Roteamento (`EngineRouter.kt`)` --references--> `GalleryDlLocalResolver`  [INFERRED]
  docs/DNR-ARCHITECTURE.md → android/app/src/main/java/com/devwolf/downloader/resolvers/GalleryDlLocalResolver.kt
- `Carrosséis Individuais (Sem `.zip`)` --references--> `HttpMediaDownloader`  [INFERRED]
  docs/DNR-ARCHITECTURE.md → android/app/src/main/java/com/devwolf/downloader/resolvers/HttpMediaDownloader.kt

## Import Cycles
- None detected.

## Communities (60 total, 18 thin omitted)

### Community 0 - "InstagramParsers"
Cohesion: 0.14
Nodes (4): InstagramPageContext, InstagramParsedMedia, InstagramParsers, InstagramParsersAndErrorTest

### Community 1 - "MainActivity.kt"
Cohesion: 0.05
Nodes (26): MainActivity, Evidência auxiliar e alcance, Fontes auxiliares selecionadas, Relação achado → evidência, Índice de evidências, Ferramentas, Metodologia e reprodução, O que não foi feito (+18 more)

### Community 2 - "package.json"
Cohesion: 0.06
Nodes (41): config, dependencies, @capacitor/android@^8.6.2, @capacitor/cli@^8.6.2, @capacitor/core@^8.6.2, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1 (+33 more)

### Community 3 - "InstagramSessionActivity.kt"
Cohesion: 0.10
Nodes (3): InstagramSessionActivity, WebChromeClient, WebViewClient

### Community 4 - "SafeHttpClient"
Cohesion: 0.13
Nodes (5): HttpTextResponse, SafeHttpClient, EventListener, EventListener, 4. Sessão Opcional via WebView e Cofre Criptografado

### Community 5 - "GalleryDlLocalResolver"
Cohesion: 0.22
Nodes (6): GalleryDlLocalResolver, ResolutionMetrics, ResolutionResult, ResolvedMediaItem, StrategyAttemptMetric, Módulos principais (`com.devwolf.downloader.resolvers`)

### Community 6 - ".resolve"
Cohesion: 0.21
Nodes (4): InstagramNativeResolver, MediaResolver, ResolutionRequest, DnrAndLocalResolversIntegrationTest

### Community 7 - "hybridOrchestrator.ts"
Cohesion: 0.22
Nodes (14): ExecutionPlan, getPlatformEngineRoute(), PLATFORM_ROUTING_TABLE, selectExecutionPlan(), ALL_11_CODES, DevwolfErrorCode, EngineOrigin, DEVWOLF_ERROR_CONTRACT (+6 more)

### Community 8 - "EngineRouter"
Cohesion: 0.19
Nodes (8): DnrTelemetrySnapshot, EngineRoutePlan, EngineRouter, PlatformRouteCapability, EngineKind, DNR, GALLERY_DL_LOCAL, YT_DLP

### Community 9 - "evidence/package.json"
Cohesion: 0.13
Nodes (14): analysis_profile, authority, caller_input_path, confidence, environment, evidence_id, evidence_links, limitations (+6 more)

### Community 10 - "verificar-erros.cjs"
Cohesion: 0.13
Nodes (9): cases, matches, moduleObject, native, projectRequire, root, sandbox, source (+1 more)

### Community 11 - "DropLink — Downloader universal self-hosted"
Cohesion: 0.06
Nodes (32): 0. Instruções para o agente de desenvolvimento, 10. Segurança, privacidade e limites, 11. Estrutura de repositório sugerida, 12. Plano de implementação e entregáveis, 13. Critérios de aceitação, 14. Referências oficiais para implementação, 15. Instrução inicial para execução no Antigravity, 1. Visão do produto (+24 more)

### Community 13 - "url.ts"
Cohesion: 0.22
Nodes (14): PlatformEngineRoute, PlatformId, ALL_ALLOWED_DOMAINS, canUseLocalEngineForRequest(), canUseLocalYtdlpForRequest(), coerceKnownSchemelessCandidate(), DomainRule, getRouteInfo() (+6 more)

### Community 14 - "ResolverException"
Cohesion: 0.24
Nodes (8): HttpMediaDownloader, HttpDownloadProgress, HttpDownloadResult, MediaItemType, AUDIO, IMAGE, VIDEO, ResolverException

### Community 15 - "LocalEngineManager"
Cohesion: 0.08
Nodes (12): LocalEngineManager, SecureVault, StoredInstagramSession, Adicionado, Etapa 1 — Fundação, Git e Contratos, Etapa 2 — Backend, Banco de Dados e Motores, Etapa 3 — Frontend, PWA e Experiência Mobile, Etapa 4 — Docker, Scripts de Operação e Documentação (+4 more)

### Community 16 - "App.tsx"
Cohesion: 0.12
Nodes (24): App(), generateIdempotencyKey(), BottomNav(), BottomNavProps, NavTab, TABS, MultiUrlModal(), MultiUrlModalProps (+16 more)

### Community 17 - "compilerOptions"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleResolution, noEmit (+11 more)

### Community 18 - "api.ts"
Cohesion: 0.09
Nodes (32): EnginesView(), EnginesViewProps, Header(), HeaderProps, HomeViewProps, SettingsView(), SettingsViewProps, ExecutionPlanOptions (+24 more)

### Community 19 - "Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais"
Cohesion: 0.20
Nodes (10): 1. Visão Geral e Separação de Responsabilidades, 2. Contrato de Erros Estruturados (`DevwolfErrorCode`), 3. Estratégias Isoladas do Instagram (DNR), 5. Avaliação do `gallery-dl` Local no Android (`GalleryDlLocalResolver.kt`), 6. Tabela Central de Roteamento (`EngineRouter.kt`), Análise do Runtime Python Embutido (`youtubedl-android:0.18.1`), Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais, Carrosséis Individuais (Sem `.zip`) (+2 more)

### Community 20 - "normalized_result"
Cohesion: 0.20
Nodes (10): normalized_result, class_count, min_sdk, package_name, permissions, resource_count, signature_verification, target_sdk (+2 more)

### Community 21 - "with"
Cohesion: 0.07
Nodes (32): name, needs, runs-on, steps, run, defaults, name, runs-on (+24 more)

### Community 23 - "HistoryView.tsx"
Cohesion: 0.16
Nodes (26): HistoryView(), HistoryViewProps, PLATFORM_FILTERS, toCarouselItem(), HomeView(), ACTIVE_STATUSES, fileToCarouselItem(), getItemLabel() (+18 more)

### Community 24 - "App"
Cohesion: 0.40
Nodes (11): buildFingerprint(), clearActiveJobById(), clearActiveJobFingerprint(), DEDUPE_WINDOW_MS, DedupeEntry, isDuplicateAutoDownload(), markActiveJobFingerprint(), markRecentAutoDownload() (+3 more)

### Community 25 - "localQueue.ts"
Cohesion: 0.26
Nodes (16): TERMINAL_STATES, useJobProgress(), applyLocalProgressToJob(), createInitialLocalJob(), getLocalJobById(), getLocalJobs(), inferMediaType(), mapLocalFilesToCarouselItems() (+8 more)

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
Cohesion: 0.18
Nodes (4): MediaProbeInfo, ProcessedMediaItem, StatusModeProcessor, StatusModeProcessorTest

### Community 37 - "provider"
Cohesion: 0.50
Nodes (4): provider, id, name, version

### Community 44 - "DevwolfDownloadService"
Cohesion: 0.08
Nodes (4): DevwolfDownloadService, PreparedLocalFile, ProgressParseResult, YtdlpMetadata

### Community 45 - "Guia de Deploy na VPS — DropLink"
Cohesion: 0.20
Nodes (9): 1. Inventário Read-Only da VPS (Execute Antes de Alterar Qualquer Coisa), 2. Configuração de Ambiente (`.env`), 3. Subindo o DropLink em Paralelo ao Cobalt, 4. Proxy Reverso e HTTPS (Mesma Origem), 5. Instalar o Atualizador Automático do Cobalt (`systemd` no Host), 6. Verificação Pós-Deploy (PWA e Share Target no Android), Exemplo Caddy, Exemplo Nginx (adicionar ao vhost existente) (+1 more)

### Community 58 - "Guia de Manutenção, Atualizações e Backup — DropLink"
Cohesion: 0.22
Nodes (8): 1. Atualização Automática e Segura do Cobalt, 2. Atualização de `yt-dlp` e `gallery-dl` (Worker), 3. Backup e Restauração (SQLite WAL + Configurações), Agendar backup diário via cron (exemplo):, Comandos úteis no host:, Como o fluxo funciona:, Guia de Manutenção, Atualizações e Backup — DropLink, Procedimento de Restauração:

### Community 60 - "DECISIONS.md — Decisões Arquiteturais"
Cohesion: 0.05
Nodes (37): 1. Regras Gerais e Idioma, 2. Matriz de Propriedade de Arquivos (Fase 3), 3. Contratos Entre Motores, Bridge Nativo (`DevwolfNative`) e UI, 4. Invariantes de Segurança, AGENTS.md — Devwolf Downloader (`com.devwolf.downloader`), Bridge `DevwolfNative` e Telemetria Local, Carrosséis Individuais & MediaStore, Roteamento de Motores (`EngineRouter`) (+29 more)

### Community 61 - "2. Problemas Comuns e Soluções"
Cohesion: 0.18
Nodes (10): 1. Limitações Reais de Plataforma (Comportamento Esperado), 2. Problemas Comuns e Soluções, App Android (`com.devwolf.downloader`) — Diagnóstico Híbrido, Cobalt aparece como "Offline" ou "Degradado" na aba Motores, Diagnóstico de Problemas e Limitações Reais — DropLink, Disco cheio ou novos jobs recusados, Erro de login / CSRF inválido após proxy reverso, Progresso não atualiza em tempo real (SSE travado) (+2 more)

### Community 62 - "DevwolfNativePlugin"
Cohesion: 0.06
Nodes (4): DevwolfNativePlugin, MediaStoreHelper, SavedMediaResult, NotificationActionReceiver

### Community 64 - "UrlExtractor.kt"
Cohesion: 0.12
Nodes (8): InstagramUrlKind, POST, REEL, SHARE, STORIES, TV, UNKNOWN, ParsedInstagramUrl

### Community 68 - "LocalQueueStore"
Cohesion: 0.14
Nodes (3): ExampleInstrumentedTest, LocalQueueStore, ExampleUnitTest

### Community 73 - "DevwolfErrorCode"
Cohesion: 0.07
Nodes (16): JobPerformanceRecord, PerformanceTracker, DevwolfErrorCode, AUTH_REQUIRED, CANCELLED, CAPTCHA_REQUIRED, EXTRACTION_FAILED, EXTRACTOR_OUTDATED (+8 more)

## Knowledge Gaps
- **23 isolated node(s):** `REEL`, `POST`, `TV`, `STORIES`, `SHARE` (+18 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 366 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **18 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Are the 2 inferred relationships involving `DevwolfDownloadService` (e.g. with `Adicionado` and `Etapa 7 — Fase 3: Devwolf Downloader v3.0.0 (100% Local Android + DNR Instagram)`) actually correct?**
  _`DevwolfDownloadService` has 2 INFERRED edges - model-reasoned connections that need verification._
- **What connects `REEL`, `POST`, `TV` to the rest of the system?**
  _23 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `InstagramParsers` be split into smaller, more focused modules?**
  _Cohesion score 0.14304993252361672 - nodes in this community are weakly interconnected._
- **Are the 6 inferred relationships involving `GalleryDlLocalResolver` (e.g. with `Roteamento de Motores (`EngineRouter`)` and `Adicionado`) actually correct?**
  _`GalleryDlLocalResolver` has 6 INFERRED edges - model-reasoned connections that need verification._
- **Should `MainActivity.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.05496828752642706 - nodes in this community are weakly interconnected._
- **Are the 2 inferred relationships involving `LocalEngineManager` (e.g. with `Adicionado` and `Etapa 7 — Fase 3: Devwolf Downloader v3.0.0 (100% Local Android + DNR Instagram)`) actually correct?**
  _`LocalEngineManager` has 2 INFERRED edges - model-reasoned connections that need verification._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.05757575757575758 - nodes in this community are weakly interconnected._