# Graph Report - Devwolf Downloader  (2026-11-17)

## Corpus Check
- 87 files · ~41,914 words
- Verdict: corpus is large enough that graph structure adds value.
- Unclassified: 12 file(s) not represented in the graph (top: (none) 4, .service 2, .timer 2)

## Summary
- 924 nodes · 2468 edges · 67 communities (48 shown, 19 thin omitted)
- Extraction: 89% EXTRACTED · 11% INFERRED · 0% AMBIGUOUS · INFERRED: 260 edges (avg confidence: 0.93)
- Token cost: 0 input · 0 output

## Community Hubs (Navigation)
- Settings
- security.py
- package.json
- cobalt_adapter.py
- job_service.py
- routes
- SessionContext
- _default_repo_root
- test_cobalt_fallback.py
- url_guard.py
- environment
- DropLink — Downloader universal self-hosted
- CancellationToken
- url.ts
- 001_initial.sql
- EngineResult
- App.tsx
- compilerOptions
- types/api.ts
- CobaltEngine
- HomeView.tsx
- jobs.py
- EngineRouter
- HistoryView.tsx
- App
- lucide-react
- BaseEngine
- droplink-worker
- droplink-api
- update-cobalt.sh
- test_gallery_dl_packages_carousel_into_ordered_zip
- router.py
- test_inflight_cancellation_terminates_worker_job
- compilerOptions
- 3. Contratos de API e Estados
- services
- settings.py
- build
- healthcheck
- update-worker.sh script
- SlidingWindowRateLimiter
- droplink-frontend-build
- EngineCircuitBreaker
- env_file
- Guia de Deploy na VPS — DropLink
- sw.js
- backup.sh
- Guia de Manutenção, Atualizações e Backup — DropLink
- DropLink — Downloader Universal Self-Hosted
- DECISIONS.md — Decisões Arquiteturais
- 2. Problemas Comuns e Soluções
- shareTarget.ts
- TASKS.md — DropLink
- README.md
- Início Rápido
- test_login_me_logout_and_csrf_enforcement

## God Nodes (most connected - your core abstractions)
1. `Settings` - 105 edges
2. `transaction()` - 39 edges
3. `EngineRequest` - 34 edges
4. `get_connection()` - 33 edges
5. `utc_now_iso()` - 32 edges
6. `CancellationToken` - 32 edges
7. `CobaltEngine` - 31 edges
8. `get_settings()` - 29 edges
9. `process_job()` - 29 edges
10. `SessionContext` - 27 edges

## Surprising Connections (you probably didn't know these)
- `login()` --uses--> `Settings`  [INFERRED]
  backend/app/api/auth.py → backend/app/config.py
- `logout()` --uses--> `Settings`  [INFERRED]
  backend/app/api/auth.py → backend/app/config.py
- `get_engines_status()` --uses--> `Settings`  [INFERRED]
  backend/app/api/engines.py → backend/app/config.py
- `get_engines_status()` --uses--> `CobaltEngine`  [INFERRED]
  backend/app/api/engines.py → backend/app/engines/cobalt_adapter.py
- `get_engines_status()` --uses--> `GalleryDlEngine`  [INFERRED]
  backend/app/api/engines.py → backend/app/engines/gallerydl_adapter.py

## Import Cycles
- None detected.

## Communities (67 total, 19 thin omitted)

### Community 0 - "Settings"
Cohesion: 0.08
Nodes (23): healthz(), readyz(), get_settings(), reset_settings_cache(), Settings, _configure_connection(), get_connection(), _migrations_dir() (+15 more)

### Community 1 - "security.py"
Cohesion: 0.19
Nodes (11): authenticate_user(), create_session(), ensure_initial_admin(), get_or_create_session_secret(), get_session_by_token(), hash_password(), hash_session_token(), revoke_session_by_token() (+3 more)

### Community 2 - "package.json"
Cohesion: 0.08
Nodes (29): devDependencies, autoprefixer@^10.4.20, jsdom@^25.0.1, postcss@^8.4.49, tailwindcss@^3.4.15, @types/react@^18.3.12, @types/react-dom@^18.3.1, typescript@^5.6.3 (+21 more)

### Community 3 - "cobalt_adapter.py"
Cohesion: 0.16
Nodes (10): _classify_ytdlp_error(), _parse_float(), _parse_int(), _resolve_ytdlp_cmd(), _execute(), create_safe_zip(), detect_mime_from_file(), FileSecurityError (+2 more)

### Community 4 - "job_service.py"
Cohesion: 0.18
Nodes (15): transaction(), utc_now(), utc_now_iso(), ensure_within_dir(), recover_expired_leases(), run_cleanup_cycle(), compute_dedup_hash(), delete_history_item() (+7 more)

### Community 5 - "routes"
Cohesion: 0.06
Nodes (31): yt-dlp, limits, max_carousel_items, max_redirect_hops, preserved_params, routes, allow_playlists, disallow_path_prefixes (+23 more)

### Community 6 - "SessionContext"
Cohesion: 0.13
Nodes (25): login(), logout(), me(), get_engines_status(), list_history(), remove_history_item(), get_current_session(), get_db() (+17 more)

### Community 8 - "test_cobalt_fallback.py"
Cohesion: 0.18
Nodes (9): EngineError, ErrorCategory, test_cobalt_redirect_ssrf_blocked(), test_cobalt_tunnel_and_picker_zip(), handler(), test_fallback_on_recoverable_vs_no_fallback_on_terminal_and_ratelimit(), cobalt_429(), cobalt_500() (+1 more)

### Community 9 - "url_guard.py"
Cohesion: 0.08
Nodes (23): consume_pending_share(), receive_share_target(), _assert_safe_hostname_syntax(), extract_first_supported_url(), _is_forbidden_ip(), _is_ip_literal(), load_routes_config(), _parse_and_check_basic_url() (+15 more)

### Community 10 - "environment"
Cohesion: 0.13
Nodes (25): environment, environment, ADMIN_PASSWORD, ADMIN_USERNAME, APP_ENV, APP_URL, COBALT_API_KEY, COBALT_API_URL (+17 more)

### Community 11 - "DropLink — Downloader universal self-hosted"
Cohesion: 0.06
Nodes (32): 0. Instruções para o agente de desenvolvimento, 10. Segurança, privacidade e limites, 11. Estrutura de repositório sugerida, 12. Plano de implementação e entregáveis, 13. Critérios de aceitação, 14. Referências oficiais para implementação, 15. Instrução inicial para execução no Antigravity, 1. Visão do produto (+24 more)

### Community 12 - "CancellationToken"
Cohesion: 0.11
Nodes (9): CancellationToken, GalleryDlEngine, _run(), _resolve_gallerydl_cmd(), YtDlpEngine, _run(), test_real_engine_binaries_installed_and_healthy(), test_real_ytdlp_download_against_local_media_server() (+1 more)

### Community 13 - "url.ts"
Cohesion: 0.25
Nodes (12): PlatformId, ALL_ALLOWED_DOMAINS, coerceKnownSchemelessCandidate(), DomainRule, extractSupportedUrlsFromText(), getRouteLabel(), isIpLiteral(), normalizeUrl() (+4 more)

### Community 14 - "001_initial.sql"
Cohesion: 0.16
Nodes (21): engine_status, files, idx_files_expires_at, idx_files_job_id, idx_job_attempts_job_id, idx_jobs_dedup, idx_jobs_idempotency, idx_jobs_lease (+13 more)

### Community 15 - "EngineResult"
Cohesion: 0.23
Nodes (9): DownloadedFileInfo, EngineResult, _classify_gallerydl_error(), _execute(), compute_sha256(), ytdlp_ok(), test_health_ready_sse_and_authenticated_file_download(), download() (+1 more)

### Community 16 - "App.tsx"
Cohesion: 0.18
Nodes (13): DEFAULT_SETTINGS, generateIdempotencyKey(), BottomNav(), BottomNavProps, NavTab, TABS, MultiUrlModal(), MultiUrlModalProps (+5 more)

### Community 17 - "compilerOptions"
Cohesion: 0.10
Nodes (19): compilerOptions, allowImportingTsExtensions, isolatedModules, jsx, lib, module, moduleResolution, noEmit (+11 more)

### Community 18 - "types/api.ts"
Cohesion: 0.12
Nodes (18): Header(), HeaderProps, ALL_ENGINES, SettingsView(), SettingsViewProps, ApiError, request(), AuthMeResponse (+10 more)

### Community 19 - "CobaltEngine"
Cohesion: 0.25
Nodes (4): _classify_cobalt_api_error(), CobaltEngine, _parse_retry_after(), read_cobalt_updater_status()

### Community 20 - "HomeView.tsx"
Cohesion: 0.31
Nodes (8): HomeView(), HomeViewProps, JobCardProps, OnboardingBanner(), OnboardingBannerProps, FormatOption, JobResponse, QualityOption

### Community 21 - "jobs.py"
Cohesion: 0.17
Nodes (18): cancel_job(), download_job_file(), get_job(), match_route(), _match_url_helper(), parse_url(), stream_job_events(), _event_stream() (+10 more)

### Community 22 - "EngineRouter"
Cohesion: 0.39
Nodes (3): ProgressUpdate, EngineRouter, MatchedRoute

### Community 23 - "HistoryView.tsx"
Cohesion: 0.17
Nodes (21): EnginesView(), getCircuitLabel(), normalizeHealth(), HistoryView(), HistoryViewProps, PLATFORM_FILTERS, ACTIVE_STATUSES, JobCard() (+13 more)

### Community 24 - "App"
Cohesion: 0.27
Nodes (16): App(), hasTriggeredAutoDownload(), markAutoDownloadTriggered(), TERMINAL_STATES, useJobProgress(), buildFingerprint(), clearActiveJobById(), clearActiveJobFingerprint() (+8 more)

### Community 25 - "lucide-react"
Cohesion: 0.19
Nodes (10): dependencies, lucide-react@^0.468.0, react@^18.3.1, react-dom@^18.3.1, LoginScreen(), LoginScreenProps, PendingSharePayload, lucide-react (+2 more)

### Community 26 - "BaseEngine"
Cohesion: 0.11
Nodes (3): BaseEngine, EngineHealth, _StubEngine

### Community 27 - "droplink-worker"
Cohesion: 0.15
Nodes (13): droplink-api, condition, cap_drop, command, container_name, depends_on, extra_hosts, image (+5 more)

### Community 28 - "droplink-api"
Cohesion: 0.15
Nodes (13): droplink-frontend-build, cap_drop, container_name, depends_on, extra_hosts, image, networks, ports (+5 more)

### Community 29 - "update-cobalt.sh"
Cohesion: 0.28
Nodes (15): check_active_cobalt_jobs(), check_cobalt_api(), check_latest_major_github(), cleanup_on_exit(), die(), get_cobalt_container_id(), get_image_digest_or_id(), load_env_file() (+7 more)

### Community 32 - "test_inflight_cancellation_terminates_worker_job"
Cohesion: 0.22
Nodes (4): test_cancel_queued_job_via_api(), test_inflight_cancellation_terminates_worker_job(), download(), health()

### Community 33 - "compilerOptions"
Cohesion: 0.22
Nodes (8): compilerOptions, allowSyntheticDefaultImports, composite, module, moduleResolution, skipLibCheck, strict, include

### Community 34 - "3. Contratos de API e Estados"
Cohesion: 0.20
Nodes (9): 1. Regras Gerais e Idioma, 2. Matriz de Propriedade de Arquivos (Swarm), 3. Contratos de API e Estados, 4. Invariantes de Segurança, AGENTS.md — DropLink, Autenticação e Segurança, Contrato do Status de Manutenção do Cobalt (`COBALT_STATUS_FILE`), Histórico, Motores, Ajustes e Share Target (+1 more)

### Community 35 - "services"
Cohesion: 0.25
Nodes (7): driver, networks, droplink_internal, services, volumes, droplink_tmp, frontend_dist

### Community 36 - "settings.py"
Cohesion: 0.47
Nodes (6): patch_settings(), read_settings(), UpdateSettingsRequest, UserSettingsResponse, get_user_settings(), update_user_settings()

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
Cohesion: 0.22
Nodes (8): 1. Atualização Automática e Segura do Cobalt, 2. Atualização de `yt-dlp` e `gallery-dl` (Worker), 3. Backup e Restauração (SQLite WAL + Configurações), Agendar backup diário via cron (exemplo):, Comandos úteis no host:, Como o fluxo funciona:, Guia de Manutenção, Atualizações e Backup — DropLink, Procedimento de Restauração:

### Community 59 - "DropLink — Downloader Universal Self-Hosted"
Cohesion: 0.22
Nodes (9): Arquitetura, Backend (Python 3.11+), Desenvolvimento Local e Testes, DropLink — Downloader Universal Self-Hosted, Escopo de Segurança e Privacidade, Frontend (Node 20+), Limites Padrão Configuráveis (`.env`), Operação, Backup e Rollback (+1 more)

### Community 60 - "DECISIONS.md — Decisões Arquiteturais"
Cohesion: 0.25
Nodes (8): 1. API FastAPI + Fila SQLite WAL + Worker Separado, 2. Progresso via SSE com Fallback para Polling, 3. Autenticação Pessoal com Argon2id, Sessão em Cookie e CSRF, 4. Proteção SSRF e Roteamento Explícito por Domínio, 5. Atualização do Cobalt via Host Systemd Timer + Rollback Automático, 6. Atualização Separada de yt-dlp e gallery-dl no Worker, 7. Frontend Servido na Mesma Origem, DECISIONS.md — Decisões Arquiteturais

### Community 61 - "2. Problemas Comuns e Soluções"
Cohesion: 0.29
Nodes (7): 2. Problemas Comuns e Soluções, Cobalt aparece como "Offline" ou "Degradado" na aba Motores, Disco cheio ou novos jobs recusados, Erro de login / CSRF inválido após proxy reverso, Progresso não atualiza em tempo real (SSE travado), Rollback do Cobalt ocorreu ou atualização ficou em cooldown, Túnel do Cobalt falha ao baixar mídia (`tunnel` / `redirect`)

### Community 62 - "shareTarget.ts"
Cohesion: 0.43
Nodes (6): api, clearPendingShareFromSession(), collectPendingShare(), consumeClientShareCache(), loadPendingShareFromSession(), savePendingShareToSession()

### Community 63 - "TASKS.md — DropLink"
Cohesion: 0.29
Nodes (6): Etapa 1 — Fundação, Git e Contratos, Etapa 2 — Backend, Banco de Dados e Motores, Etapa 3 — Frontend, PWA e Experiência Mobile, Etapa 4 — Docker, Scripts de Operação e Documentação, Etapa 5 — Graphify e Verificação Integrada, TASKS.md — DropLink

### Community 65 - "Início Rápido"
Cohesion: 0.50
Nodes (4): 1. Pré-requisitos, 2. Configuração, 3. Executar com Docker Compose, Início Rápido

## Knowledge Gaps
- **19 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `Settings` connect `Settings` to `test_inflight_cancellation_terminates_worker_job`, `security.py`, `cobalt_adapter.py`, `settings.py`, `job_service.py`, `SessionContext`, `_default_repo_root`, `test_cobalt_fallback.py`, `url_guard.py`, `EngineCircuitBreaker`, `CancellationToken`, `EngineResult`, `CobaltEngine`, `jobs.py`, `EngineRouter`, `BaseEngine`, `test_gallery_dl_packages_carousel_into_ordered_zip`, `router.py`?**
  _High betweenness centrality (0.079) - this node is a cross-community bridge._
- **Are the 60 inferred relationships involving `Settings` (e.g. with `login()` and `logout()`) actually correct?**
  _`Settings` has 60 INFERRED edges - model-reasoned connections that need verification._
- **Should `Settings` be split into smaller, more focused modules?**
  _Cohesion score 0.08022598870056497 - nodes in this community are weakly interconnected._
- **Why does `test_gallery_dl_packages_carousel_into_ordered_zip()` connect `test_gallery_dl_packages_carousel_into_ordered_zip` to `Settings`, `CancellationToken`, `router.py`?**
  _High betweenness centrality (0.011) - this node is a cross-community bridge._
- **Are the 12 inferred relationships involving `EngineRequest` (e.g. with `CobaltEngine` and `GalleryDlEngine`) actually correct?**
  _`EngineRequest` has 12 INFERRED edges - model-reasoned connections that need verification._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.08064516129032258 - nodes in this community are weakly interconnected._
- **Why does `UrlValidationError` connect `url_guard.py` to `cobalt_adapter.py`, `job_service.py`, `SessionContext`, `CobaltEngine`, `jobs.py`?**
  _High betweenness centrality (0.009) - this node is a cross-community bridge._