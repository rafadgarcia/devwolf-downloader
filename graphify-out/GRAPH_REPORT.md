# Graph Report - Devwolf Downloader  (2026-11-17)

## Corpus Check
- 100 files · ~73,446 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 23 file(s) not represented in the graph (top: .xml 11, (none) 5, .properties 2)

## Summary
- 1147 nodes · 2439 edges · 60 communities (43 shown, 17 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 181 edges (avg confidence: 0.91)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `0b6c3404`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- InstagramParsers
- MainActivity.kt
- package.json
- InstagramSessionActivity.kt
- SafeHttpClient
- GalleryDlLocalResolver
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
- Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais
- normalized_result
- with
- .resolveReddit
- HistoryView.tsx
- Auditoria Diagnóstica `v3.0.0-rc.1` — Instagram (`CAPTCHA_REQUIRED`) e Reddit (`PRIVATE_MEDIA`)
- localQueue.ts
- InstagramNativeResolver
- ExampleInstrumentedTest.java
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
- UrlExtractor
- DevwolfNativePlugin
- DnrAndLocalResolversIntegrationTest.kt
- LocalQueueStore
- DevwolfErrorCode

## God Nodes (most connected - your core abstractions)
1. `DevwolfDownloadService` - 46 edges
2. `GalleryDlLocalResolver` - 41 edges
3. `DevwolfNativePlugin` - 30 edges
4. `SecureVault` - 29 edges
5. `SafeHttpClient` - 29 edges
6. `App()` - 29 edges
7. `LocalEngineManager` - 28 edges
8. `InstagramParsers` - 24 edges
9. `PerformanceTracker` - 22 edges
10. `DevwolfNativePlugin` - 22 edges

## Surprising Connections (you probably didn't know these)
- `Adicionado` --references--> `DevwolfDownloadService`  [INFERRED]
  CHANGELOG.md → android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt
- `4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`)` --references--> `LocalEngineManager`  [INFERRED]
  docs/diagnostics/rc1/reddit-audit.md → android/app/src/main/java/com/devwolf/downloader/LocalEngineManager.kt
- `1. Entrada e seleção do post` --references--> `MainActivity`  [INFERRED]
  docs/rev-eng/RELATORIO.md → android/app/src/main/java/com/devwolf/downloader/MainActivity.kt
- `Carrosséis Individuais & MediaStore` --references--> `MediaStoreHelper`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/MediaStoreHelper.kt
- `Bridge `DevwolfNative` e Telemetria Local` --references--> `PerformanceTracker`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/PerformanceTracker.kt

## Import Cycles
- None detected.

## Communities (60 total, 17 thin omitted)

### Community 0 - "InstagramParsers"
Cohesion: 0.17
Nodes (3): InstagramParsedMedia, InstagramParsers, InstagramParsersAndErrorTest

### Community 1 - "MainActivity.kt"
Cohesion: 0.07
Nodes (25): Evidência auxiliar e alcance, Fontes auxiliares selecionadas, Relação achado → evidência, Índice de evidências, Ferramentas, Metodologia e reprodução, O que não foi feito, Repetir a inspeção (+17 more)

### Community 2 - "package.json"
Cohesion: 0.06
Nodes (41): config, dependencies, @capacitor/android@^8.6.2, @capacitor/cli@^8.6.2, @capacitor/core@^8.6.2, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1 (+33 more)

### Community 3 - "InstagramSessionActivity.kt"
Cohesion: 0.10
Nodes (3): InstagramSessionActivity, WebChromeClient, WebViewClient

### Community 4 - "SafeHttpClient"
Cohesion: 0.14
Nodes (5): HttpTextResponse, SafeHttpClient, EventListener, EventListener, 4. Sessão Opcional via WebView e Cofre Criptografado

### Community 6 - "ResolverException"
Cohesion: 0.26
Nodes (8): ErrorClassifier, MediaResolver, ResolutionMetrics, ResolutionRequest, ResolutionResult, ResolverException, StrategyAttemptMetric, Módulos principais (`com.devwolf.downloader.resolvers`)

### Community 7 - "hybridOrchestrator.ts"
Cohesion: 0.21
Nodes (15): ExecutionPlan, getPlatformEngineRoute(), PLATFORM_ROUTING_TABLE, selectExecutionPlan(), ALL_11_CODES, DevwolfErrorCode, EngineOrigin, DEVWOLF_ERROR_CONTRACT (+7 more)

### Community 8 - "EngineRouter"
Cohesion: 0.13
Nodes (11): Roteamento de Motores (`EngineRouter`), DnrTelemetrySnapshot, EngineRoutePlan, EngineRouter, PlatformRouteCapability, EngineKind, DNR, GALLERY_DL_LOCAL (+3 more)

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
Cohesion: 0.12
Nodes (3): MediaStoreHelper, SavedMediaResult, NotificationActionReceiver

### Community 13 - "url.ts"
Cohesion: 0.14
Nodes (22): MultiUrlModal(), MultiUrlModalProps, ClipboardPermissionStatus, UseClipboardAutoDetectOptions, ExecutionPlanOptions, PlatformEngineRoute, nativeBridge, PlatformId (+14 more)

### Community 14 - "ResolvedMediaItem"
Cohesion: 0.18
Nodes (9): HttpMediaDownloader, HttpDownloadProgress, HttpDownloadResult, MediaItemType, AUDIO, IMAGE, VIDEO, ResolvedMediaItem (+1 more)

### Community 15 - "LocalEngineManager"
Cohesion: 0.06
Nodes (15): LocalEngineManager, SecureVault, StoredInstagramSession, Adicionado, 3. Investigação 2: Uso da Sessão Autenticada do Instagram pelo DNR, Lacunas na Estratégia 3 (`AuthorizedWebApiStrategy`) e Extração de `media_id`, Rastreamento Completo da Cadeia de Sessão, Etapa 1 — Fundação, Git e Contratos (+7 more)

### Community 16 - "App.tsx"
Cohesion: 0.12
Nodes (31): App(), generateIdempotencyKey(), BottomNav(), BottomNavProps, NavTab, TABS, useClipboardAutoDetect(), TERMINAL_STATES (+23 more)

### Community 17 - "compilerOptions"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleResolution, noEmit (+11 more)

### Community 18 - "api.ts"
Cohesion: 0.12
Nodes (28): EnginesView(), EnginesViewProps, Header(), HeaderProps, HomeViewProps, SettingsView(), SettingsViewProps, DevwolfNative (+20 more)

### Community 19 - "Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais"
Cohesion: 0.20
Nodes (10): 1. Visão Geral e Separação de Responsabilidades, 2. Contrato de Erros Estruturados (`DevwolfErrorCode`), 3. Estratégias Isoladas do Instagram (DNR), 5. Avaliação do `gallery-dl` Local no Android (`GalleryDlLocalResolver.kt`), 6. Tabela Central de Roteamento (`EngineRouter.kt`), Análise do Runtime Python Embutido (`youtubedl-android:0.18.1`), Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais, Carrosséis Individuais (Sem `.zip`) (+2 more)

### Community 20 - "normalized_result"
Cohesion: 0.20
Nodes (10): normalized_result, class_count, min_sdk, package_name, permissions, resource_count, signature_verification, target_sdk (+2 more)

### Community 21 - "with"
Cohesion: 0.07
Nodes (32): name, needs, runs-on, steps, run, defaults, name, runs-on (+24 more)

### Community 22 - ".resolveReddit"
Cohesion: 0.16
Nodes (12): Corrigido, Alterações de Código Implementadas, 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`, 2. Causas-Raiz Comprovadas, 3. Matriz de Diferenciação de Respostas HTTP do Reddit, 4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`), Auditoria Diagnóstica RC1 — Reddit (`PRIVATE_MEDIA` em Conteúdo Público), Causa-Raiz R1 — Mapeamento Incondicional de HTTP `403` para `PRIVATE_MEDIA` (`GalleryDlLocalResolver.kt:155-162`) (+4 more)

### Community 23 - "HistoryView.tsx"
Cohesion: 0.20
Nodes (17): HistoryViewProps, HomeView(), ACTIVE_STATUSES, fileToCarouselItem(), getItemLabel(), getItemStatusLabel(), JobCard(), JobCardProps (+9 more)

### Community 24 - "Auditoria Diagnóstica `v3.0.0-rc.1` — Instagram (`CAPTCHA_REQUIRED`) e Reddit (`PRIVATE_MEDIA`)"
Cohesion: 0.12
Nodes (15): 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`, 1. Resumo Executivo dos Resultados Físicos (`v3.0.0-rc.1`), 2. Investigação 1: Falsos Positivos de `CAPTCHA_REQUIRED` no Instagram, 4. Investigação 3: Interrupção Prematura das Estratégias do DNR e Fallback, 5. Investigação 4: Recomparação Detalhada com Evidências do Rposty (REA/JADX), 6. Investigação 5: Classificação Incorreta de `PRIVATE_MEDIA` no Reddit, 7. Investigação 6: Issues Recentes Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`), 8. Investigação 7: Testes Reproduzíveis de Diagnóstico (`Rc1DiagnosticAuditTest.kt`) (+7 more)

### Community 25 - "localQueue.ts"
Cohesion: 0.20
Nodes (24): HistoryView(), PLATFORM_FILTERS, toCarouselItem(), applyLocalProgressToJob(), createInitialLocalJob(), filterLocalHistory(), getLocalJobById(), getLocalJobs() (+16 more)

### Community 27 - "InstagramNativeResolver"
Cohesion: 0.23
Nodes (5): InstagramNativeResolver, InstagramPageContext, Rc1DiagnosticAuditTest, Resultados dos Testes (`v3.0.0-rc.2`), Etapa 8 — v3.0.0-rc.2: Correções Instagram (DNR/CAPTCHA) & Reddit (403/Share Links)

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
Cohesion: 0.16
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
Nodes (36): 1. Regras Gerais e Idioma, 2. Matriz de Propriedade de Arquivos (Fase 3), 3. Contratos Entre Motores, Bridge Nativo (`DevwolfNative`) e UI, 4. Invariantes de Segurança, AGENTS.md — Devwolf Downloader (`com.devwolf.downloader`), Bridge `DevwolfNative` e Telemetria Local, Carrosséis Individuais & MediaStore, Taxonomia Estruturada de Erros (`DevwolfErrorCode`) (+28 more)

### Community 61 - "2. Problemas Comuns e Soluções"
Cohesion: 0.18
Nodes (10): 1. Limitações Reais de Plataforma (Comportamento Esperado), 2. Problemas Comuns e Soluções, App Android (`com.devwolf.downloader`) — Diagnóstico Híbrido, Cobalt aparece como "Offline" ou "Degradado" na aba Motores, Diagnóstico de Problemas e Limitações Reais — DropLink, Disco cheio ou novos jobs recusados, Erro de login / CSRF inválido após proxy reverso, Progresso não atualiza em tempo real (SSE travado) (+2 more)

### Community 64 - "UrlExtractor"
Cohesion: 0.06
Nodes (11): LocalGalleryCapabilities, InstagramUrlKind, POST, REEL, SHARE, STORIES, TV, UNKNOWN (+3 more)

### Community 67 - "DnrAndLocalResolversIntegrationTest.kt"
Cohesion: 0.11
Nodes (8): Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, ExportApk

### Community 73 - "DevwolfErrorCode"
Cohesion: 0.07
Nodes (15): JobPerformanceRecord, PerformanceTracker, DevwolfErrorCode, AUTH_REQUIRED, CANCELLED, CAPTCHA_REQUIRED, EXTRACTION_FAILED, EXTRACTOR_OUTDATED (+7 more)

## Knowledge Gaps
- **23 isolated node(s):** `REEL`, `POST`, `TV`, `STORIES`, `SHARE` (+18 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 372 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **17 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `MainActivity` connect `LocalQueueStore` to `MainActivity.kt`?**
  _High betweenness centrality (0.280) - this node is a cross-community bridge._
- **Are the 7 inferred relationships involving `DevwolfDownloadService` (e.g. with `Adicionado` and `Corrigido`) actually correct?**
  _`DevwolfDownloadService` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `REEL`, `POST`, `TV` to the rest of the system?**
  _23 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.07389162561576355 - nodes in this community are weakly interconnected._
- **Why does `1. Entrada e seleção do post` connect `MainActivity.kt` to `LocalQueueStore`?**
  _High betweenness centrality (0.274) - this node is a cross-community bridge._
- **Are the 11 inferred relationships involving `GalleryDlLocalResolver` (e.g. with `Roteamento de Motores (`EngineRouter`)` and `.redditClassifiesFastly403WafAsRecoverableExtractionFailedAndRealPrivateAsTerminal()`) actually correct?**
  _`GalleryDlLocalResolver` has 11 INFERRED edges - model-reasoned connections that need verification._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.05757575757575758 - nodes in this community are weakly interconnected._