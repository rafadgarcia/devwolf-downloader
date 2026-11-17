# DropLink — Downloader Universal Self-Hosted

**Copiou. Abriu. Baixou.**

O **DropLink** é uma PWA pessoal self-hosted para baixar vídeos, fotos e carrosséis públicos autorizados de múltiplas plataformas (Instagram, TikTok, YouTube, X/Twitter, Facebook, Reddit e Pinterest), orquestrando automaticamente **yt-dlp**, **gallery-dl** e uma instância existente do **Cobalt**, com fallback inteligente e atualização controlada.

---

## Principais Recursos

- **PWA Mobile-First (`pt-BR`)**: tema escuro padrão (`#101012` / `#A3E635`), área de toque >= 44 px e tema claro opcional.
- **Clipboard Inteligente**: leitura opcional em `mount`/`focus`/`visibilitychange` (debounce 500 ms + mutex, sem polling contínuo). Extração e normalização de URL **100% locais** no navegador; nunca envia texto bruto do clipboard ao servidor. Se houver múltiplas URLs válidas, solicita escolha explícita.
- **Web Share Target (Android)**: recebe links compartilhados via menu do sistema (`POST /share-target`), inclusive quando a URL vem dentro do campo `text`, mantendo proteção de sessão e deduplicação.
- **Roteamento Estrito & Proteção SSRF**: allowlist explícita em [`config/routes.yaml`](config/routes.yaml), rejeição de IPs privados/loopback/link-local, credenciais embutidas, domínios impostores e validação de redirects/DNS.
- **Orquestração & Fallback**:
  - Fila persistente em **SQLite WAL** com leases atômicos e recuperação pós-reinício.
  - Worker separado com **yt-dlp**, **gallery-dl** (empacotando carrosséis em `.zip` único) e adaptador HTTP para **Cobalt**.
  - Circuit breaker por motor (5 minutos após 3 falhas consecutivas de infraestrutura) e fallback ordenado para falhas recuperáveis (nunca insiste em mídia privada, CAPTCHA ou bloqueio de acesso).
- **Progresso Real (SSE + Polling Fallback)**: exibe apenas bytes/percentuais reais ou spinner indeterminado quando o tamanho total é desconhecido.
- **Atualização Segura do Cobalt (sem Watchtower)**: timer `systemd` no host verifica digest da tag major fixada (`ghcr.io/imputnet/cobalt:11`), drena jobs Cobalt ativos com status em JSON read-only, valida readiness em 90s e realiza **rollback automático** com cooldown em caso de falha.

---

## Arquitetura

```text
PWA (React/Vite/TS) ──► FastAPI (Auth Argon2id, CSRF, SQLite WAL, SSE)
                              │
                              ▼
                     Worker Python (não-root)
                 ┌──────┼──────────────┐
                 ▼      ▼              ▼
              yt-dlp  gallery-dl    Cobalt existente
                                       ▲
                    Host systemd timer ┘ (ops/update-cobalt.sh + rollback)
```

Decisões detalhadas em [`DECISIONS.md`](DECISIONS.md) e contratos em [`AGENTS.md`](AGENTS.md).

---

## Início Rápido

### 1. Pré-requisitos
- Docker + Docker Compose v2
- Instância existente do Cobalt (acessível por rede Docker ou endpoint interno)
- Proxy reverso com HTTPS (Nginx, Caddy ou Traefik) para uso completo em dispositivos móveis
- No host (para os scripts em `ops/`): `bash`, `python3`, `flock`, `curl` e permissão Docker/systemd

### 2. Configuração
```bash
cp .env.example .env
chmod 600 .env
# Edite .env com APP_URL, ADMIN_PASSWORD, SESSION_SECRET, COBALT_API_URL, COBALT_HOST_HEALTH_URL e COBALT_COMPOSE_FILE
```

### 3. Executar com Docker Compose
```bash
mkdir -p data/db data/storage data/ops-status backups
# Necessário para que o usuário não-root do container (UID/GID 10001) escreva no SQLite/storage
sudo chown -R 10001:10001 data/db data/storage

docker compose build
docker compose up -d
```

Consulte o passo a passo completo (incluindo comandos de inventário read-only da VPS, `compose.cobalt-net.yml` e proxy SSE) em [`docs/deployment.md`](docs/deployment.md).

---

## Limites Padrão Configuráveis (`.env`)

| Variável | Padrão | Finalidade |
| --- | --- | --- |
| `MAX_CONCURRENT_JOBS` | `2` | Trabalhos simultâneos no worker |
| `MAX_QUEUED_JOBS_PER_USER` | `20` | Limite máximo de fila por usuário |
| `MAX_JOB_SIZE_MB` | `500` | Tamanho máximo por job em streaming |
| `MAX_CAROUSEL_ITEMS` | `50` | Máximo de itens por carrossel (`gallery-dl` / `picker`) |
| `MAX_JOB_DURATION_SECONDS` | `600` | Timeout máximo total por job (10 min) |
| `MIN_FREE_DISK_MB` | `2048` | Espaço livre mínimo para aceitar novos jobs (2 GB) |
| `FILE_RETENTION_HOURS` | `24` | Retenção de arquivos baixados |
| `HISTORY_RETENTION_DAYS` | `30` | Retenção do histórico no SQLite |

---

## Operação, Backup e Rollback

- **Atualização do Cobalt (timer `systemd` 30m via `OnCalendar=*:0/30`, personalizável com `systemctl edit cobalt-update.timer`)**: [`docs/maintenance.md`](docs/maintenance.md) e [`ops/update-cobalt.sh`](ops/update-cobalt.sh)
- **Verificação e atualização de `yt-dlp` / `gallery-dl`**:
  ```bash
  bash ops/check-engine-updates.sh
  bash ops/update-worker.sh --apply
  ```
- **Backup consistente (SQLite online + configs)**:
  ```bash
  bash ops/backup.sh
  ```
- **Diagnóstico e limitações reais** (Clipboard HTTPS/gesto, iOS sem Web Share Target, bloqueios de provedores): [`docs/troubleshooting.md`](docs/troubleshooting.md)

---

## Desenvolvimento Local e Testes

### Backend (Python 3.11+)
```bash
cd backend
python -m venv .venv
source .venv/bin/activate  # ou .venv\Scripts\activate no Windows
pip install -r requirements.txt
pytest -q
```

### Frontend (Node 20+)
```bash
cd frontend
npm install
npm test
npm run build
```

---

## Escopo de Segurança e Privacidade
- Uso estritamente pessoal autenticado (Argon2id + cookie `HttpOnly`/`SameSite=Lax`/`Secure` + CSRF + rate limiting).
- Sem cadastro público, sem anúncios, sem rastreadores e sem cache de API/mídia no Service Worker.
- Não contorna DRM, contas privadas ou desafios anti-bot. Destinado exclusivamente a conteúdo próprio ou com autorização do titular.
