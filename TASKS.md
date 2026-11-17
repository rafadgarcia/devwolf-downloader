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
