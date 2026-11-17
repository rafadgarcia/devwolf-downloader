# Graph Report - Devwolf Downloader  (2026-11-17)

## Corpus Check
- 121 files · ~64,876 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 29 file(s) not represented in the graph (top: .xml 10, (none) 6, .properties 2)

## Summary
- 1345 nodes · 3510 edges · 82 communities (50 shown, 32 thin omitted)
- Extraction: 91% EXTRACTED · 9% INFERRED · 0% AMBIGUOUS · INFERRED: 302 edges (avg confidence: 0.93)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `b1b855a7`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- config.py
- security.py
- package.json
- cobalt_adapter.py
- runner.py
- routes
- job_service.py
- Settings
- test_cobalt_fallback.py
- url_guard.py
- environment
- DropLink — Downloader universal self-hosted
- CancellationToken
- url.ts
- 001_initial.sql
- LocalEngineManager
- App.tsx
- compilerOptions
- types/api.ts
- engines.py
- nativeBridge.ts
- with
- HistoryView.tsx
- App
- localQueue.ts
- router.py
- droplink-worker
- droplink-api
- update-cobalt.sh
- test_gallery_dl_packages_carousel_into_ordered_zip
- test_phase2_status_mode.py
- test_inflight_cancellation_terminates_worker_job
- compilerOptions
- 3. Contratos de API e Estados
- services
- .processForStatusMode
- build
- healthcheck
- update-worker.sh script
- SlidingWindowRateLimiter
- droplink-frontend-build
- transaction
- env_file
- DevwolfDownloadService
- Guia de Deploy na VPS — DropLink
- sw.js
- backup.sh
- Guia de Manutenção, Atualizações e Backup — DropLink
- DropLink — Downloader Universal Self-Hosted
- DECISIONS.md — Decisões Arquiteturais
- 2. Problemas Comuns e Soluções
- DevwolfNativePlugin
- TASKS.md — DropLink
- MediaStoreHelper.kt
- Início Rápido
- DevwolfNativePlugin
- StatusModeProcessorTest.kt
- ExampleInstrumentedTest.java
- .onReceive
- Changelog — Devwolf Downloader
- receive_share_target

## God Nodes (most connected - your core abstractions)
1. `Settings` - 117 edges
2. `transaction()` - 41 edges
3. `EngineRequest` - 36 edges
4. `utc_now_iso()` - 35 edges
5. `get_connection()` - 35 edges
6. `App()` - 35 edges
7. `CancellationToken` - 34 edges
8. `process_job()` - 33 edges
9. `CobaltEngine` - 32 edges
10. `EngineError` - 31 edges

## Surprising Connections (you probably didn't know these)
- `Adicionado` --references--> `DevwolfDownloadService`  [INFERRED]
  CHANGELOG.md → android/app/src/main/java/com/devwolf/downloader/DevwolfDownloadService.kt
- `login()` --uses--> `Settings`  [INFERRED]
  backend/app/api/auth.py → backend/app/config.py
- `logout()` --uses--> `Settings`  [INFERRED]
  backend/app/api/auth.py → backend/app/config.py
- `_build_platform_capabilities()` --uses--> `Settings`  [INFERRED]
  backend/app/api/engines.py → backend/app/config.py
- `get_engines_status()` --uses--> `SessionContext`  [INFERRED]
  backend/app/api/engines.py → backend/app/auth/security.py

## Import Cycles
- None detected.

## Communities (82 total, 32 thin omitted)

### Community 0 - "config.py"
Cohesion: 0.12
Nodes (11): reset_settings_cache(), create_app(), auth_client(), client(), db_conn(), test_settings(), test_login_me_logout_and_csrf_enforcement(), test_login_rate_limiting() (+3 more)

### Community 1 - "security.py"
Cohesion: 0.20
Nodes (11): authenticate_user(), create_session(), ensure_initial_admin(), get_or_create_session_secret(), get_session_by_token(), hash_password(), hash_session_token(), revoke_session_by_token() (+3 more)

### Community 2 - "package.json"
Cohesion: 0.06
Nodes (40): config, dependencies, @capacitor/android@^8.6.2, @capacitor/cli@^8.6.2, @capacitor/core@^8.6.2, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1 (+32 more)

### Community 3 - "cobalt_adapter.py"
Cohesion: 0.13
Nodes (15): download_job_file(), ProgressUpdate, _classify_gallerydl_error(), _execute(), _classify_ytdlp_error(), _parse_float(), _parse_int(), _resolve_ytdlp_cmd() (+7 more)

### Community 4 - "runner.py"
Cohesion: 0.13
Nodes (13): _configure_connection(), get_connection(), _migrations_dir(), run_migrations(), lifespan(), _periodic_cleanup_loop(), recover_expired_leases(), run_cleanup_cycle() (+5 more)

### Community 5 - "routes"
Cohesion: 0.06
Nodes (31): yt-dlp, limits, max_carousel_items, max_redirect_hops, preserved_params, routes, allow_playlists, disallow_path_prefixes (+23 more)

### Community 6 - "job_service.py"
Cohesion: 0.07
Nodes (53): login(), logout(), me(), list_history(), remove_history_item(), cancel_job(), get_job(), match_route() (+45 more)

### Community 7 - "Settings"
Cohesion: 0.18
Nodes (6): healthz(), readyz(), _default_repo_root(), get_settings(), Settings, test_initial_admin_without_hardcoded_password()

### Community 8 - "test_cobalt_fallback.py"
Cohesion: 0.15
Nodes (9): EngineError, ErrorCategory, _classify_cobalt_api_error(), EngineRouter, MatchedRoute, test_fallback_on_recoverable_vs_no_fallback_on_terminal_and_ratelimit(), cobalt_429(), cobalt_500() (+1 more)

### Community 9 - "url_guard.py"
Cohesion: 0.09
Nodes (23): _assert_safe_hostname_syntax(), extract_first_supported_url(), _is_forbidden_ip(), _is_ip_literal(), load_routes_config(), _parse_and_check_basic_url(), _path_matches_prefix(), resolve_redirects_safely() (+15 more)

### Community 10 - "environment"
Cohesion: 0.13
Nodes (25): environment, environment, ADMIN_PASSWORD, ADMIN_USERNAME, APP_ENV, APP_URL, COBALT_API_KEY, COBALT_API_URL (+17 more)

### Community 11 - "DropLink — Downloader universal self-hosted"
Cohesion: 0.06
Nodes (32): 0. Instruções para o agente de desenvolvimento, 10. Segurança, privacidade e limites, 11. Estrutura de repositório sugerida, 12. Plano de implementação e entregáveis, 13. Critérios de aceitação, 14. Referências oficiais para implementação, 15. Instrução inicial para execução no Antigravity, 1. Visão do produto (+24 more)

### Community 12 - "CancellationToken"
Cohesion: 0.11
Nodes (8): GalleryDlEngine, _run(), _resolve_gallerydl_cmd(), YtDlpEngine, _run(), test_real_engine_binaries_installed_and_healthy(), test_real_ytdlp_download_against_local_media_server(), __init__()

### Community 13 - "url.ts"
Cohesion: 0.11
Nodes (27): MultiUrlModal(), MultiUrlModalProps, ClipboardPermissionStatus, UseClipboardAutoDetectOptions, decorateVpsJob(), ExecutionPlan, ExecutionPlanOptions, isTerminalPlatformError() (+19 more)

### Community 14 - "001_initial.sql"
Cohesion: 0.16
Nodes (21): engine_status, files, idx_files_expires_at, idx_files_job_id, idx_job_attempts_job_id, idx_jobs_dedup, idx_jobs_idempotency, idx_jobs_lease (+13 more)

### Community 15 - "LocalEngineManager"
Cohesion: 0.06
Nodes (4): LocalEngineManager, LocalQueueStore, MainActivity, SecureVault

### Community 16 - "App.tsx"
Cohesion: 0.11
Nodes (28): App(), generateIdempotencyKey(), BottomNav(), BottomNavProps, NavTab, TABS, Header(), HeaderProps (+20 more)

### Community 17 - "compilerOptions"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleResolution, noEmit (+11 more)

### Community 18 - "types/api.ts"
Cohesion: 0.07
Nodes (34): EnginesView(), EnginesViewProps, getCircuitLabel(), normalizeHealth(), ALL_ENGINES, EXECUTION_MODES, SettingsViewProps, VAULT_PLATFORMS (+26 more)

### Community 19 - "engines.py"
Cohesion: 0.12
Nodes (13): _build_platform_capabilities(), _fetch_pypi_latest_version(), get_engines_releases(), get_engines_status(), _is_newer_version(), _normalize_ver(), CancellationToken, CobaltEngine (+5 more)

### Community 20 - "nativeBridge.ts"
Cohesion: 0.22
Nodes (10): JobRunContext, HomeViewProps, DevwolfNative, LocalDownloadProgressEvent, LocalDownloadSnapshot, LocalOutputFile, ShareIntentPayload, FormatOption (+2 more)

### Community 21 - "with"
Cohesion: 0.06
Nodes (38): name, needs, runs-on, steps, defaults, name, runs-on, steps (+30 more)

### Community 23 - "HistoryView.tsx"
Cohesion: 0.18
Nodes (23): HistoryView(), HistoryViewProps, PLATFORM_FILTERS, HomeView(), MODE_LABELS, ACTIVE_STATUSES, JobCard(), JobCardProps (+15 more)

### Community 24 - "App"
Cohesion: 0.40
Nodes (11): buildFingerprint(), clearActiveJobById(), clearActiveJobFingerprint(), DEDUPE_WINDOW_MS, DedupeEntry, isDuplicateAutoDownload(), markActiveJobFingerprint(), markRecentAutoDownload() (+3 more)

### Community 25 - "localQueue.ts"
Cohesion: 0.18
Nodes (23): hasTriggeredAutoDownload(), markAutoDownloadTriggered(), TERMINAL_STATES, useJobProgress(), buildApiUrl(), getAuthHeaders(), getBearerToken(), getJobFileDownloadUrl() (+15 more)

### Community 26 - "router.py"
Cohesion: 0.13
Nodes (10): BaseEngine, DownloadedFileInfo, EngineHealth, EngineRequest, EngineResult, _StubEngine, ytdlp_ok(), test_health_ready_sse_and_authenticated_file_download() (+2 more)

### Community 27 - "droplink-worker"
Cohesion: 0.15
Nodes (13): droplink-api, condition, cap_drop, command, container_name, depends_on, extra_hosts, image (+5 more)

### Community 28 - "droplink-api"
Cohesion: 0.15
Nodes (13): droplink-frontend-build, cap_drop, container_name, depends_on, extra_hosts, image, networks, ports (+5 more)

### Community 29 - "update-cobalt.sh"
Cohesion: 0.28
Nodes (15): check_active_cobalt_jobs(), check_cobalt_api(), check_latest_major_github(), cleanup_on_exit(), die(), get_cobalt_container_id(), get_image_digest_or_id(), load_env_file() (+7 more)

### Community 31 - "test_phase2_status_mode.py"
Cohesion: 0.13
Nodes (18): ensure_within_dir(), _build_processed_entry(), MediaProbeInfo, probe_media(), process_media_for_status(), ProcessedFileEntry, ProcessedMediaOutput, resolve_binary() (+10 more)

### Community 32 - "test_inflight_cancellation_terminates_worker_job"
Cohesion: 0.22
Nodes (4): test_cancel_queued_job_via_api(), test_inflight_cancellation_terminates_worker_job(), download(), health()

### Community 33 - "compilerOptions"
Cohesion: 0.22
Nodes (8): compilerOptions, allowSyntheticDefaultImports, composite, module, moduleResolution, skipLibCheck, strict, include

### Community 34 - "3. Contratos de API e Estados"
Cohesion: 0.20
Nodes (9): 1. Regras Gerais e Idioma, 2. Matriz de Propriedade de Arquivos (Swarm), 3. Contratos de API e Estados, 4. Invariantes de Segurança, AGENTS.md — Devwolf Downloader (antigo DropLink), Autenticação e Segurança, Contrato do Status de Manutenção do Cobalt (`COBALT_STATUS_FILE`), Histórico, Motores, Ajustes e Share Target (+1 more)

### Community 35 - "services"
Cohesion: 0.25
Nodes (7): driver, networks, droplink_internal, services, volumes, droplink_tmp, frontend_dist

### Community 36 - ".processForStatusMode"
Cohesion: 0.19
Nodes (4): MediaProbeInfo, ProcessedMediaItem, StatusModeProcessor, StatusModeProcessorTest

### Community 37 - "build"
Cohesion: 0.47
Nodes (6): context, dockerfile, target, build, build, build

### Community 38 - "healthcheck"
Cohesion: 0.48
Nodes (7): healthcheck, healthcheck, interval, retries, start_period, test, timeout

### Community 39 - "update-worker.sh script"
Cohesion: 0.31
Nodes (7): load_env_file(), resolve_path(), check-engine-updates.sh script, load_env_file(), log(), resolve_path(), update-worker.sh script

### Community 41 - "droplink-frontend-build"
Cohesion: 0.29
Nodes (7): cap_drop, command, image, restart, security_opt, volumes, droplink-frontend-build

### Community 42 - "transaction"
Cohesion: 0.18
Nodes (12): consume_pending_share(), transaction(), utc_now(), utc_now_iso(), EngineCircuitBreaker, delete_history_item(), acquire_next_job(), process_job() (+4 more)

### Community 43 - "env_file"
Cohesion: 0.67
Nodes (4): env_file, env_file, path, required

### Community 45 - "Guia de Deploy na VPS — DropLink"
Cohesion: 0.22
Nodes (9): 1. Inventário Read-Only da VPS (Execute Antes de Alterar Qualquer Coisa), 2. Configuração de Ambiente (`.env`), 3. Subindo o DropLink em Paralelo ao Cobalt, 4. Proxy Reverso e HTTPS (Mesma Origem), 5. Instalar o Atualizador Automático do Cobalt (`systemd` no Host), 6. Verificação Pós-Deploy (PWA e Share Target no Android), Exemplo Caddy, Exemplo Nginx (adicionar ao vhost existente) (+1 more)

### Community 47 - "backup.sh"
Cohesion: 0.70
Nodes (4): load_env_file(), log(), resolve_path(), backup.sh script

### Community 58 - "Guia de Manutenção, Atualizações e Backup — DropLink"
Cohesion: 0.17
Nodes (8): 1. Atualização Automática e Segura do Cobalt, 2. Atualização de `yt-dlp` e `gallery-dl` (Worker), 3. Backup e Restauração (SQLite WAL + Configurações), Agendar backup diário via cron (exemplo):, Comandos úteis no host:, Como o fluxo funciona:, Guia de Manutenção, Atualizações e Backup — DropLink, Procedimento de Restauração:

### Community 59 - "DropLink — Downloader Universal Self-Hosted"
Cohesion: 0.20
Nodes (10): App Android Híbrido (JDK 21 + Android SDK + Capacitor), Arquitetura, Backend (Python 3.11+), Desenvolvimento Local e Testes, Devwolf Downloader — Downloader Universal Híbrido (Android + Self-Hosted VPS), Escopo de Segurança e Privacidade, Frontend (Node 20+), Limites Padrão Configuráveis (`.env`) (+2 more)

### Community 60 - "DECISIONS.md — Decisões Arquiteturais"
Cohesion: 0.22
Nodes (9): 1. API FastAPI + Fila SQLite WAL + Worker Separado, 2. Progresso via SSE com Fallback para Polling, 3. Autenticação Pessoal com Argon2id, Sessão em Cookie e CSRF, 4. Proteção SSRF e Roteamento Explícito por Domínio, 5. Atualização do Cobalt via Host Systemd Timer + Rollback Automático, 6. Atualização Separada de yt-dlp e gallery-dl no Worker, 7. Frontend Servido na Mesma Origem, 8. Arquitetura Híbrida Android (Capacitor + Motor Nativo Kotlin) — Fase 2 (+1 more)

### Community 61 - "2. Problemas Comuns e Soluções"
Cohesion: 0.18
Nodes (10): 1. Limitações Reais de Plataforma (Comportamento Esperado), 2. Problemas Comuns e Soluções, App Android (`com.devwolf.downloader`) — Diagnóstico Híbrido, Cobalt aparece como "Offline" ou "Degradado" na aba Motores, Diagnóstico de Problemas e Limitações Reais — DropLink, Disco cheio ou novos jobs recusados, Erro de login / CSRF inválido após proxy reverso, Progresso não atualiza em tempo real (SSE travado) (+2 more)

### Community 63 - "TASKS.md — DropLink"
Cohesion: 0.25
Nodes (7): Etapa 1 — Fundação, Git e Contratos, Etapa 2 — Backend, Banco de Dados e Motores, Etapa 3 — Frontend, PWA e Experiência Mobile, Etapa 4 — Docker, Scripts de Operação e Documentação, Etapa 5 — Graphify e Verificação Integrada, Etapa 6 — Fase 2: Devwolf Downloader Híbrido (Android + VPS), TASKS.md — DropLink

### Community 65 - "Início Rápido"
Cohesion: 0.50
Nodes (4): 1. Pré-requisitos, 2. Configuração, 3. Executar com Docker Compose, Início Rápido

### Community 70 - "Changelog — Devwolf Downloader"
Cohesion: 0.33
Nodes (5): [1.0.0] — Fase 1: Baseline Self-Hosted PWA, [2.0.0] — Fase 2: App Híbrido Android, Adicionado, Adicionado, Changelog — Devwolf Downloader

## Knowledge Gaps
- **32 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Settings` connect `Settings` to `config.py`, `security.py`, `test_inflight_cancellation_terminates_worker_job`, `cobalt_adapter.py`, `runner.py`, `job_service.py`, `receive_share_target`, `test_cobalt_fallback.py`, `url_guard.py`, `transaction`, `CancellationToken`, `engines.py`, `router.py`, `test_gallery_dl_packages_carousel_into_ordered_zip`, `test_phase2_status_mode.py`?**
  _High betweenness centrality (0.056) - this node is a cross-community bridge._
- **Are the 70 inferred relationships involving `Settings` (e.g. with `login()` and `logout()`) actually correct?**
  _`Settings` has 70 INFERRED edges - model-reasoned connections that need verification._
- **Should `config.py` be split into smaller, more focused modules?**
  _Cohesion score 0.11965811965811966 - nodes in this community are weakly interconnected._
- **Why does `DevwolfDownloadService` connect `DevwolfDownloadService` to `.onReceive`, `Changelog — Devwolf Downloader`, `.load`, `.handleOnDestroy`, `LocalEngineManager`, `DevwolfDownloadService.kt`?**
  _High betweenness centrality (0.028) - this node is a cross-community bridge._
- **Are the 13 inferred relationships involving `EngineRequest` (e.g. with `CobaltEngine` and `GalleryDlEngine`) actually correct?**
  _`EngineRequest` has 13 INFERRED edges - model-reasoned connections that need verification._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.05813953488372093 - nodes in this community are weakly interconnected._
- **Why does `DevwolfNativePlugin` connect `DevwolfNativePlugin` to `MediaStoreHelper.kt`, `.load`, `.handleOnDestroy`, `LocalEngineManager`, `DevwolfDownloadService.kt`?**
  _High betweenness centrality (0.028) - this node is a cross-community bridge._