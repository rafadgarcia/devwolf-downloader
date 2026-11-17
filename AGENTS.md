# AGENTS.md — Devwolf Downloader (antigo DropLink)

Normas permanentes de desenvolvimento, propriedade de arquivos, contratos entre componentes e invariantes de segurança. Fonte de verdade de produto: `DropLink-PROJETO.md` (Fase 1) e especificações da Fase 2 (App Híbrido Android `com.devwolf.downloader`).

## 1. Regras Gerais e Idioma
- Nome oficial da aplicação: **Devwolf Downloader** (`com.devwolf.downloader`).
- Interface do usuário e documentação principal: **Português do Brasil (`pt-BR`)**.
- Não alterar infraestrutura real de VPS (containers, volumes, proxies, certificados) sem inventário read-only prévio e autorização explícita.
- Reutilizar a instância existente do Cobalt via `COBALT_API_URL` (e `COBALT_API_KEY` opcional). Nunca destruir ou substituir containers existentes.
- Não implementar acesso a mídias privadas, contorno de DRM, login de terceiros por padrão ou resolução de CAPTCHA.

## 2. Matriz de Propriedade de Arquivos (Swarm)
- **Fase 1**: `@devops` (`.gitignore`, `.env.example`, `compose.yml`, `README.md`, `AGENTS.md`, `TASKS.md`, `DECISIONS.md`, `config/routes.yaml`, `ops/*`, `docs/*`, `graphify-out/*`), `@backend` (`backend/**`), `@frontend` (`frontend/**`).
- **Fase 2 (App Híbrido Android)**:
  - `@android`: `android/**`, `frontend/capacitor.config.ts`, `.github/workflows/**`, `AGENTS.md`, `TASKS.md`, `DECISIONS.md`, `CHANGELOG.md`, `README.md`, `docs/**`, `.gitignore`, `graphify-out/**`.
  - `@backend-2`: `backend/**`, `config/routes.yaml`.
  - `@frontend-2`: `frontend/**` (exceto `frontend/capacitor.config.ts`).

## 3. Contratos de API e Estados

### Autenticação e Segurança
- Cookie de sessão `HttpOnly`, `SameSite=Lax`, `Secure` (configurável por env `COOKIE_SECURE=true|false` para dev local HTTP).
- Endpoints:
  - `POST /api/auth/login` -> `{ "password": "..." }` (ou `{ "username": "...", "password": "..." }` com default admin)
  - `POST /api/auth/logout` -> encerra sessão
  - `GET /api/auth/me` -> `{ "authenticated": true, "user": { "id", "username" }, "csrf_token": "..." }`
- Endpoints de escrita (`POST`, `PATCH`, `DELETE`, exceto `/api/auth/login` e `/share-target`) exigem header `X-CSRF-Token` válido e validação Same-Origin.

### Jobs, Fases e SSE
- `POST /api/jobs` -> `{ "url": "...", "format": "auto|video|audio|image", "quality": "best|1080p|720p|480p|audio", "idempotency_key": "...", "force_manual": false }`
- Estados (`status`): `queued`, `resolving`, `downloading`, `processing`, `ready`, `failed`, `cancelled`, `expired`.
- Fases em pt-BR recomendadas na UI: `"Link detectado"`, `"Procurando mídia"`, `"Baixando"`, `"Preparando arquivo"`, `"Pronto para baixar"`, `"Falhou"`.
- Progresso honesto: `bytes_downloaded` (`int`), `total_bytes` (`int | null`), `percent` (`float | null`), `is_indeterminate` (`bool`). Nunca simular percentual quando `total_bytes` for desconhecido.
- `GET /api/jobs/{id}/events`: Server-Sent Events (`text/event-stream`), com fallback automático no frontend para `GET /api/jobs/{id}`.
- `POST /api/jobs/{id}/cancel`: cancela job ativo e encerra subprocessos/streams.
- `GET /api/jobs/{id}/files/{file_id}`: download autenticado com `Content-Disposition: attachment; filename="..."` e validação de posse do usuário.

### Histórico, Motores, Ajustes e Share Target
- `GET /api/history` (paginado, filtros `q`, `platform`, `status`) e `DELETE /api/history/{id}`.
- `GET /api/engines`: status (`online`, `degraded`, `maintenance`, `offline`), versão, circuit breaker, último sucesso, último erro sanitizado e status do atualizador Cobalt.
- `GET /api/settings` e `PATCH /api/settings`: `auto_download`, `default_quality`, `data_saver`, `file_retention_hours`, `theme`, `enabled_engines`.
- `POST /share-target`: recebe `application/x-www-form-urlencoded` (`title`, `text`, `url`), extrai URL segura sem criar job antes da autenticação e redireciona para o app autenticado via estado curto/sessão (sem vazar URLs sensíveis em query/logs).
- `GET /healthz` e `GET /readyz`: liveness e readiness minimalistas.

### Contrato do Status de Manutenção do Cobalt (`COBALT_STATUS_FILE`)
Arquivo JSON escrito atomicamente pelo host (`ops/update-cobalt.sh`) e montado read-only para backend/worker (ex.: `/app/data/cobalt-updater-status.json`):
```json
{
  "maintenance": false,
  "state": "idle",
  "current_digest": "sha256:...",
  "current_version": "11.x",
  "previous_digest": "sha256:...",
  "rejected_digest": null,
  "rejected_until": null,
  "last_check_at": "2026-11-17T00:00:00Z",
  "last_update_at": "2026-11-17T00:00:00Z",
  "last_rollback_at": null,
  "last_error": null
}
```
Quando `maintenance: true`, o worker não inicia novos jobs no Cobalt e aguarda dreno limitado antes da atualização, mantendo `yt-dlp` e `gallery-dl` ativos.

## 4. Invariantes de Segurança
- **SSRF**: parsear URL com biblioteca padrão (`urllib.parse`), exigir `http`/`https`, proibir credenciais embutidas, IPs literais, loopback, redes privadas RFC1918, link-local (`169.254.0.0/16`), IPv6 equivalentes e domínios fora da allowlist de `config/routes.yaml`. Validar cada salto de redirect e resolver DNS com verificação de IP antes da conexão.
- **Execução segura**: containers/processos sem root; subprocessos sempre com lista de argumentos (`shell=False`); nunca confiar em título remoto como nome de arquivo; prevenir path traversal, symlink escape e zip-slip.
- **Privacidade**: nunca enviar texto bruto do clipboard ao servidor; service worker não cacheia `/api/*`, histórico ou arquivos de mídia; não logar tokens, cookies ou URLs assinadas.
