# TASKS.md — DropLink

Checklist de acompanhamento do projeto.

## Etapa 1 — Fundação, Git e Contratos
- [x] Inicializar repositório Git e configurar `.gitignore`
- [x] Criar `AGENTS.md` com regras, propriedade e contratos de API/motores
- [x] Criar `DECISIONS.md` com decisões arquiteturais
- [x] Criar `config/routes.yaml` com allowlist de domínios, tracking params e fallback ordenado
- [x] Verificar ambiente local e disponibilidade do `graphify`

## Etapa 2 — Backend, Banco de Dados e Motores
- [x] Schema SQLite WAL e migrações versionadas (`users`, `sessions`, `settings`, `jobs`, `job_attempts`, `files`, `engine_status`, `maintenance_events`)
- [x] Autenticação Argon2id, cookies seguros, CSRF e rate limiting
- [x] Validador rigoroso de URL, normalização e proteção SSRF (DNS + redirects)
- [x] Adaptadores uniformes: `yt-dlp`, `gallery-dl` (com ZIP para carrossel) e `cobalt` (tunnel/redirect/picker/error)
- [x] Worker separado com lease atômico, circuit breaker, fallback ordenado e limpeza periódica
- [x] Endpoints REST + SSE + `/share-target` + `/healthz` / `/readyz`
- [x] Testes automatizados (`pytest`) cobrindo segurança, fila, fallback, cancelamento e fluxo real

## Etapa 3 — Frontend, PWA e Experiência Mobile
- [x] Estrutura React + TypeScript + Vite + Tailwind (`pt-BR`, dark mode `#101012` / `#1C1C20` / `#A3E635`)
- [x] Tela de login e preservação de compartilhamento pós-login
- [x] Clipboard inteligente (mount/focus/visibilitychange com debounce 500ms, mutex e extração local)
- [x] Fluxo manual, detecção instantânea de plataforma, deduplicação de 10 minutos e seletor de múltiplas URLs
- [x] Progresso real via SSE + fallback polling, barra honesta (indeterminada quando sem tamanho) e entrega de arquivo
- [x] Abas Histórico, Motores (saúde + avisos reais) e Ajustes
- [x] Manifest PWA (`share_target` POST), ícones e Service Worker seguro (sem cache de API/mídia)
- [x] Build de produção e testes unitários (`vitest`)

## Etapa 4 — Docker, Scripts de Operação e Documentação
- [x] Criar `.env.example` e `compose.yml` (backend, worker não-root, builder PWA, volume SQLite/mídia, integração com Cobalt externo)
- [x] Implementar `ops/update-cobalt.sh`, `ops/cobalt-update.service` e `ops/cobalt-update.timer` com `flock`, digest check e rollback automático
- [x] Implementar `ops/check-engine-updates.sh`, `ops/update-worker.sh` e `ops/backup.sh`
- [x] Redigir `README.md` e `docs/{deployment,maintenance,troubleshooting}.md` em pt-BR com inventário read-only e limitações reais

## Etapa 5 — Graphify e Verificação Integrada
- [x] Gerar grafo AST/estrutural em `graphify-out/` (`graph.json` e `GRAPH_REPORT.md`)
- [x] Validar `docker compose config`, sintaxe dos scripts bash (`bash -n`) e estrutura integrada

## Etapa 6 — Fase 2: Devwolf Downloader Híbrido (Android + VPS)
- [x] Criar repositório privado `rafadgarcia/devwolf-downloader` e publicar baseline `v1.0.0`
- [x] Configurar ambiente Android SDK/JDK/Capacitor e publicar contrato `DevwolfNative`
- [x] Implementar camada nativa Kotlin (`android/`): `yt-dlp` + `FFmpeg`, Modo Status, MediaStore, `ACTION_SEND`, Foreground Service, notificações, updater/rollback e cofre `EncryptedSharedPreferences`
- [x] Atualizar backend (`CORS`/`Bearer`, Modo Status FFmpeg, rotas Facebook `/share/r/`) e frontend (UI híbrida, Modo Status, histórico unificado)
- [x] Build do APK (`assembleDebug`), testes unitários (`pytest`, `vitest`, `testDebugUnitTest`), CI/CD GitHub Actions e Release `v2.0.0` com APK anexado

## Etapa 7 — Fase 3: Devwolf Downloader v3.0.0 (100% Local Android + DNR Instagram)
- [x] Preservar arquitetura híbrida na branch `archive/vps-hybrid` e tag `v2.0.0`, atualizar `.gitignore` (excluindo `docs/Rposty.apk` e `docs/rev-eng/sources/`) e limpar infraestrutura VPS da `main`
- [x] Implementar `Devwolf Native Resolver (DNR)` para Instagram (`EmbedCaptionedStrategy`, `WebPageJsonStrategy`, `AuthorizedWebApiStrategy`), `SafeHttpClient` anti-SSRF, `HttpMediaDownloader` e `GalleryDlLocalResolver` (Pinterest, Reddit, Twitter/X imagens)
- [x] Implementar contrato estruturado de 11 erros (`DevwolfErrorCode`), `EngineRouter` central e login opcional via `InstagramSessionActivity` (WebView oficial + `SecureVault`)
- [x] Eliminar `getInfo()` duplicado no `DevwolfDownloadService` (passo único `--no-simulate --no-warnings --print before_dl:...`) e tornar `YoutubeDL`/`FFmpeg` lazy-loaded
- [x] Corrigir `LocalEngineManager` para ler versão real do binário `yt-dlp`, suportar auto-check inicial/24h, canais `Stable`/`Nightly`, validação pós-update e rollback
- [x] Publicar itens de carrossel individualmente no `MediaStore` (rejeitando `.zip`) e exibir progresso/ações por item na UI
- [x] Remover login/fallback/configurações de VPS do frontend, adicionar `PerformanceTracker` + área de Diagnóstico/Performance sanitizada e otimizar APK com R8 + ABI splits mantendo assinatura compatível com `v2.0.0`

## Etapa 8 — v3.0.0-rc.2: Correções Instagram (DNR/CAPTCHA) & Reddit (403/Share Links)
- [x] Eliminar falsos positivos de `CAPTCHA_REQUIRED` em `InstagramParsers.classifyFailure` ignorando `<script>`/`<style>`/`<noscript>`/`<meta>`/`<link>` da shell Polaris e restringindo detecção a redirects `/challenge/` / `/checkpoint/`, JSON estruturado e DOM visível
- [x] Extrair `media_id` de `<meta property="al:ios:url">` e `al:android:url` em `InstagramParsers.extractPageContext` e priorizar execução *session-first* (`WebPageJsonStrategy` -> `AuthorizedWebApiStrategy` -> `EmbedCaptionedStrategy`) no `InstagramNativeResolver`
- [x] Alinhar `--user-agent` do fallback `yt-dlp` em `DevwolfDownloadService` com o `User-Agent` salvo da sessão WebView do Instagram e registrar `strategyAttempts` e `httpStatus` no `PerformanceTracker`
- [x] Diferenciar bloqueio WAF/Fastly HTTP 403 (`EXTRACTION_FAILED` recuperável + fallback `old.reddit.com` + `yt-dlp`) de conteúdo realmente privado (`PRIVATE_MEDIA`) no `GalleryDlLocalResolver` e `ErrorClassifier`, além de resolver links `/r/<sub>/s/<id>` sem anexar `.json` em paths não resolvidos
- [x] Bump para `v3.0.0-rc.2` (`versionCode 30002`), suítes de testes (`vitest` + `testDebugUnitTest`), build de APKs assinados, atualização do `graphify-out/` e prerelease no GitHub
