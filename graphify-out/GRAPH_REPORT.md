# Graph Report - Devwolf Downloader  (2026-11-18)

## Corpus Check
- 107 files · ~90,465 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 24 file(s) not represented in the graph (top: .xml 12, (none) 5, .properties 2)

## Summary
- 1325 nodes · 2936 edges · 63 communities (47 shown, 16 thin omitted)
- Extraction: 92% EXTRACTED · 8% INFERRED · 0% AMBIGUOUS · INFERRED: 223 edges (avg confidence: 0.91)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `645a4a14`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- InstagramParsers
- MainActivity.kt
- package.json
- InstagramSessionActivity.kt
- InstagramNativeResolver
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
- SecureVault
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
- PerformanceAndErrorContractTest.kt
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
- App
- ExampleInstrumentedTest.java
- Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais
- DevwolfDownloadService
- Guia de Deploy na VPS — DropLink
- sw.js
- Guia de Manutenção, Atualizações e Backup — DropLink
- DECISIONS.md — Decisões Arquiteturais
- 2. Problemas Comuns e Soluções
- DevwolfNativePlugin
- UrlExtractor
- DevwolfNativePlugin
- Rc3EnginesRegressionTest.kt
- LocalQueueStore
- DevwolfErrorCode

## God Nodes (most connected - your core abstractions)
1. `DevwolfDownloadService` - 52 edges
2. `GalleryDlLocalResolver` - 47 edges
3. `SecureVault` - 44 edges
4. `SafeHttpClient` - 35 edges
5. `DevwolfNativePlugin` - 32 edges
6. `LocalEngineManager` - 31 edges
7. `App()` - 30 edges
8. `ResolvedMediaItem` - 27 edges
9. `ResolverException` - 27 edges
10. `InstagramParsers` - 24 edges

## Surprising Connections (you probably didn't know these)
- `4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`)` --references--> `LocalEngineManager`  [INFERRED]
  docs/diagnostics/rc1/reddit-audit.md → android/app/src/main/java/com/devwolf/downloader/LocalEngineManager.kt
- `Carrosséis Individuais & MediaStore` --references--> `MediaStoreHelper`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/MediaStoreHelper.kt
- `Bridge `DevwolfNative` e Telemetria Local` --references--> `PerformanceTracker`  [INFERRED]
  AGENTS.md → android/app/src/main/java/com/devwolf/downloader/PerformanceTracker.kt
- `5. Investigação 4: Recomparação Detalhada com Evidências do Rposty (REA/JADX)` --references--> `SecureVault`  [INFERRED]
  docs/diagnostics/rc1/README.md → android/app/src/main/java/com/devwolf/downloader/SecureVault.kt
- `Solução Adotada: Resolvedores Estruturados Nativos em Kotlin` --references--> `SecureVault`  [INFERRED]
  docs/DNR-ARCHITECTURE.md → android/app/src/main/java/com/devwolf/downloader/SecureVault.kt

## Import Cycles
- None detected.

## Communities (63 total, 16 thin omitted)

### Community 0 - "InstagramParsers"
Cohesion: 0.17
Nodes (3): InstagramParsedMedia, InstagramParsers, InstagramParsersAndErrorTest

### Community 1 - "MainActivity.kt"
Cohesion: 0.08
Nodes (24): Evidência auxiliar e alcance, Fontes auxiliares selecionadas, Relação achado → evidência, Índice de evidências, Ferramentas, Metodologia e reprodução, O que não foi feito, Repetir a inspeção (+16 more)

### Community 2 - "package.json"
Cohesion: 0.06
Nodes (39): config, dependencies, @capacitor/android@^8.6.2, @capacitor/cli@^8.6.2, @capacitor/core@^8.6.2, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1 (+31 more)

### Community 3 - "InstagramSessionActivity.kt"
Cohesion: 0.09
Nodes (3): InstagramSessionActivity, WebChromeClient, WebViewClient

### Community 5 - "GalleryDlLocalResolver"
Cohesion: 0.22
Nodes (3): 1. Pinterest: Correção de Pins de Vídeo (`GalleryDlLocalResolver.kt` & `HttpMediaDownloader.kt`), Causa Raiz Comprovada (`v3.0.0-rc.2`), Correções Implementadas

### Community 6 - "ResolverException"
Cohesion: 0.26
Nodes (7): ErrorClassifier, MediaResolver, ResolutionMetrics, ResolutionRequest, ResolutionResult, StrategyAttemptMetric, Módulos principais (`com.devwolf.downloader.resolvers`)

### Community 7 - "hybridOrchestrator.ts"
Cohesion: 0.19
Nodes (18): vitest@^2.1.8, ExecutionPlan, getPlatformEngineRoute(), PLATFORM_ROUTING_TABLE, selectExecutionPlan(), ALL_11_CODES, DevwolfErrorCode, EngineOrigin (+10 more)

### Community 8 - "EngineRouter"
Cohesion: 0.15
Nodes (10): DnrTelemetrySnapshot, EngineRoutePlan, EngineRouter, PlatformRouteCapability, EngineKind, DNR, GALLERY_DL_LOCAL, YT_DLP (+2 more)

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
Cohesion: 0.10
Nodes (3): MediaStoreHelper, SavedMediaResult, NotificationActionReceiver

### Community 13 - "url.ts"
Cohesion: 0.16
Nodes (19): MultiUrlModalProps, ClipboardPermissionStatus, useClipboardAutoDetect(), UseClipboardAutoDetectOptions, ExecutionPlanOptions, isNativePlatform(), ALL_ALLOWED_DOMAINS, canUseLocalEngineForRequest() (+11 more)

### Community 14 - "ResolvedMediaItem"
Cohesion: 0.22
Nodes (9): HttpMediaDownloader, HttpDownloadProgress, HttpDownloadResult, MediaItemType, AUDIO, IMAGE, VIDEO, ResolvedMediaItem (+1 more)

### Community 15 - "SecureVault"
Cohesion: 0.06
Nodes (21): LocalEngineManager, SecureVault, StoredInstagramSession, StoredPlatformSession, [1.0.0] — Fase 1: Baseline Self-Hosted PWA, [3.0.0] — Fase 3: Android 100% Local, DNR Instagram, Carrosséis Individuais & Otimização, [3.0.0-rc.2] — Correções de Diagnóstico RC1 (Instagram & Reddit), [3.0.0-rc.3] — Pinterest Vídeo, TikTok `yt-dlp`, Threads Nativo, Contas Multiplataforma & Diagnóstico Completo (+13 more)

### Community 16 - "App.tsx"
Cohesion: 0.13
Nodes (20): AddLinkModal(), Header(), HeaderProps, NavTab, VIEW_TITLES, HowToDownloadModal(), HowToDownloadModalProps, MultiUrlModal() (+12 more)

### Community 17 - "compilerOptions"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleResolution, noEmit (+11 more)

### Community 18 - "api.ts"
Cohesion: 0.12
Nodes (28): AddLinkModalProps, EnginesView(), EnginesViewProps, ACCOUNT_PLATFORMS, ManagedAccountPlatform, SettingsView(), SettingsViewProps, DevwolfNative (+20 more)

### Community 19 - "TASKS.md — DropLink"
Cohesion: 0.12
Nodes (14): 2. Investigação 1: Falsos Positivos de `CAPTCHA_REQUIRED` no Instagram, Localização Exata, Mecanismo da Falha (Comprovado), Etapa 10 — v3.0.0-rc.4: Redesign Rposty, Tema Roxo Glassmorphism & Thumbnails Nativas, Etapa 1 — Fundação, Git e Contratos, Etapa 2 — Backend, Banco de Dados e Motores, Etapa 3 — Frontend, PWA e Experiência Mobile, Etapa 4 — Docker, Scripts de Operação e Documentação (+6 more)

### Community 20 - "normalized_result"
Cohesion: 0.20
Nodes (10): normalized_result, class_count, min_sdk, package_name, permissions, resource_count, signature_verification, target_sdk (+2 more)

### Community 21 - "with"
Cohesion: 0.07
Nodes (32): name, needs, runs-on, steps, run, defaults, name, runs-on (+24 more)

### Community 22 - ".resolveReddit"
Cohesion: 0.19
Nodes (9): GalleryDlLocalResolver, 6. Investigação 5: Classificação Incorreta de `PRIVATE_MEDIA` no Reddit, Alterações de Código Implementadas, 2. Causas-Raiz Comprovadas, Causa-Raiz R1 — Mapeamento Incondicional de HTTP `403` para `PRIVATE_MEDIA` (`GalleryDlLocalResolver.kt:155-162`), Causa-Raiz R2 — Cabeçalhos Propensos a Bloqueio Fastly/WAF e Omissão de Cookie (`GalleryDlLocalResolver.kt:125-128`), Causa-Raiz R3 — Falha Silenciosa em Links de Compartilhamento Móvel `/r/<sub>/s/<share_id>` (`SafeHttpClient.kt:180-230`, `GalleryDlLocalResolver.kt:135-145, 801-808`), Causa-Raiz R4 — Comportamento quando `yt-dlp` é Alcançado (`ErrorClassifier.fromYtdlpOutput`) (+1 more)

### Community 23 - "HomeView.tsx"
Cohesion: 0.11
Nodes (41): HeroActionsModal(), HeroActionsModalProps, HistoryView(), HistoryViewProps, PLATFORM_FILTERS, toCarouselItem(), ACTIVE_STATUSES, fileToCarouselItem() (+33 more)

### Community 24 - "Auditoria Diagnóstica `v3.0.0-rc.1` — Instagram (`CAPTCHA_REQUIRED`) e Reddit (`PRIVATE_MEDIA`)"
Cohesion: 0.14
Nodes (13): 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`, 1. Resumo Executivo dos Resultados Físicos (`v3.0.0-rc.1`), 3. Investigação 2: Uso da Sessão Autenticada do Instagram pelo DNR, 4. Investigação 3: Interrupção Prematura das Estratégias do DNR e Fallback, 5. Investigação 4: Recomparação Detalhada com Evidências do Rposty (REA/JADX), 7. Investigação 6: Issues Recentes Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`), 8. Investigação 7: Testes Reproduzíveis de Diagnóstico (`Rc1DiagnosticAuditTest.kt`), 9. Causas Comprovadas, Hipóteses Restantes e Plano de Correções Recomendadas (+5 more)

### Community 25 - "localQueue.ts"
Cohesion: 0.23
Nodes (20): TERMINAL_STATES, useJobProgress(), applyLocalProgressToJob(), createInitialLocalJob(), filterLocalHistory(), getLocalJobById(), getLocalJobs(), inferMediaType() (+12 more)

### Community 27 - "PerformanceAndErrorContractTest.kt"
Cohesion: 0.06
Nodes (11): LocalGalleryCapabilities, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher, Dispatcher (+3 more)

### Community 28 - ".redditMobileShareLinksSendHtmlAcceptExtractCanonicalAndNeverAppendJsonToSharePath"
Cohesion: 0.18
Nodes (7): InstagramPageContext, Rc1DiagnosticAuditTest, 10. Correções Implementadas e Validadas na `v3.0.0-rc.2`, 3. Matriz de Diferenciação de Respostas HTTP do Reddit, 4. Achados em Issues Upstream (`yt-dlp`, `gallery-dl`, `youtubedl-android`), Auditoria Diagnóstica RC1 — Reddit (`PRIVATE_MEDIA` em Conteúdo Público), Resultados dos Testes (`v3.0.0-rc.2`)

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

### Community 38 - "App"
Cohesion: 0.35
Nodes (13): App(), generateIdempotencyKey(), buildFingerprint(), clearActiveJobById(), clearActiveJobFingerprint(), DEDUPE_WINDOW_MS, DedupeEntry, isDuplicateAutoDownload() (+5 more)

### Community 40 - "Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais"
Cohesion: 0.20
Nodes (10): 1. Visão Geral e Separação de Responsabilidades, 2. Contrato de Erros Estruturados (`DevwolfErrorCode`), 3. Estratégias Isoladas do Instagram (DNR), 5. Avaliação do `gallery-dl` Local no Android (`GalleryDlLocalResolver.kt`), 6. Tabela Central de Roteamento (`EngineRouter.kt`), Análise do Runtime Python Embutido (`youtubedl-android:0.18.1`), Arquitetura do Devwolf Native Resolver (DNR) e Motores Locais, Carrosséis Individuais (Sem `.zip`) (+2 more)

### Community 44 - "DevwolfDownloadService"
Cohesion: 0.06
Nodes (9): DevwolfDownloadService, PreparedLocalFile, ProgressParseResult, YtdlpExecutionOutcome, YtdlpFailureException, YtdlpMetadata, [2.0.0] — Fase 2: App Híbrido Android, Adicionado (+1 more)

### Community 45 - "Guia de Deploy na VPS — DropLink"
Cohesion: 0.20
Nodes (9): 1. Inventário Read-Only da VPS (Execute Antes de Alterar Qualquer Coisa), 2. Configuração de Ambiente (`.env`), 3. Subindo o DropLink em Paralelo ao Cobalt, 4. Proxy Reverso e HTTPS (Mesma Origem), 5. Instalar o Atualizador Automático do Cobalt (`systemd` no Host), 6. Verificação Pós-Deploy (PWA e Share Target no Android), Exemplo Caddy, Exemplo Nginx (adicionar ao vhost existente) (+1 more)

### Community 58 - "Guia de Manutenção, Atualizações e Backup — DropLink"
Cohesion: 0.22
Nodes (8): 1. Atualização Automática e Segura do Cobalt, 2. Atualização de `yt-dlp` e `gallery-dl` (Worker), 3. Backup e Restauração (SQLite WAL + Configurações), Agendar backup diário via cron (exemplo):, Comandos úteis no host:, Como o fluxo funciona:, Guia de Manutenção, Atualizações e Backup — DropLink, Procedimento de Restauração:

### Community 60 - "DECISIONS.md — Decisões Arquiteturais"
Cohesion: 0.06
Nodes (29): 1. Regras Gerais e Idioma, 2. Matriz de Propriedade de Arquivos (Fase 3), 3. Contratos Entre Motores, Bridge Nativo (`DevwolfNative`) e UI, 4. Invariantes de Segurança, AGENTS.md — Devwolf Downloader (`com.devwolf.downloader`), Bridge `DevwolfNative` e Telemetria Local, Carrosséis Individuais & MediaStore, Roteamento de Motores (`EngineRouter`) (+21 more)

### Community 61 - "2. Problemas Comuns e Soluções"
Cohesion: 0.18
Nodes (10): 1. Limitações Reais de Plataforma (Comportamento Esperado), 2. Problemas Comuns e Soluções, App Android (`com.devwolf.downloader`) — Diagnóstico Híbrido, Cobalt aparece como "Offline" ou "Degradado" na aba Motores, Diagnóstico de Problemas e Limitações Reais — DropLink, Disco cheio ou novos jobs recusados, Erro de login / CSRF inválido após proxy reverso, Progresso não atualiza em tempo real (SSE travado) (+2 more)

### Community 64 - "UrlExtractor"
Cohesion: 0.05
Nodes (17): ThreadsNativeResolver, ThreadsParsedMedia, InstagramUrlKind, POST, REEL, SHARE, STORIES, TV (+9 more)

### Community 67 - "Rc3EnginesRegressionTest.kt"
Cohesion: 0.06
Nodes (9): HttpTextResponse, SafeHttpClient, EventListener, EventListener, InMemorySharedPreferences, Editor, Rc3EnginesRegressionTest, REMOVED (+1 more)

### Community 68 - "LocalQueueStore"
Cohesion: 0.08
Nodes (6): LocalQueueStore, MainActivity, [3.0.0-rc.4] — Redesign Rposty, Tema Roxo Glassmorphism, Thumbnails Nativas & Barra Roxa, Adicionado, Alterado, 1. Entrada e seleção do post

### Community 73 - "DevwolfErrorCode"
Cohesion: 0.06
Nodes (18): JobPerformanceRecord, PerformanceTracker, DevwolfErrorCode, AUTH_REQUIRED, CANCELLED, CAPTCHA_REQUIRED, EXTRACTION_FAILED, EXTRACTOR_OUTDATED (+10 more)

## Knowledge Gaps
- **26 isolated node(s):** `REEL`, `POST`, `TV`, `STORIES`, `SHARE` (+21 more)
  These have ≤1 connection - possible missing edges or undocumented components. (Counts symbols only; 401 node(s) total have ≤1 connection when file, concept and rationale nodes are included.)
- **16 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Adicionado` connect `SecureVault` to `ResolverException`, `hybridOrchestrator.ts`?**
  _High betweenness centrality (0.254) - this node is a cross-community bridge._
- **Are the 7 inferred relationships involving `DevwolfDownloadService` (e.g. with `Adicionado` and `Corrigido`) actually correct?**
  _`DevwolfDownloadService` has 7 INFERRED edges - model-reasoned connections that need verification._
- **What connects `REEL`, `POST`, `TV` to the rest of the system?**
  _26 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `MainActivity.kt` be split into smaller, more focused modules?**
  _Cohesion score 0.07671957671957672 - nodes in this community are weakly interconnected._
- **Why does `getUserFacingErrorMessage()` connect `hybridOrchestrator.ts` to `localQueue.ts`, `HomeView.tsx`, `SecureVault`?**
  _High betweenness centrality (0.217) - this node is a cross-community bridge._
- **Are the 12 inferred relationships involving `GalleryDlLocalResolver` (e.g. with `Roteamento de Motores (`EngineRouter`)` and `.redditClassifiesFastly403WafAsRecoverableExtractionFailedAndRealPrivateAsTerminal()`) actually correct?**
  _`GalleryDlLocalResolver` has 12 INFERRED edges - model-reasoned connections that need verification._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.059800664451827246 - nodes in this community are weakly interconnected._