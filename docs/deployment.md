# Guia de Deploy na VPS — DropLink

Este guia descreve como inventariar a VPS existente em modo **somente leitura**, conectar o DropLink à instância do Cobalt já em execução sem remover ou sobrescrever serviços existentes e configurar HTTPS com proxy reverso para suporte completo a PWA, Clipboard e SSE.

---

## 1. Inventário Read-Only da VPS (Execute Antes de Alterar Qualquer Coisa)

Não assuma distribuição Linux, portas, proxy reverso, rede Docker ou caminho do `compose.yml` do Cobalt. Execute primeiro apenas comandos de leitura:

```bash
# 1. Identificar sistema e recursos disponíveis
uname -a
cat /etc/os-release
df -h
free -h

# 2. Listar containers, redes e projetos Docker Compose existentes
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'
docker compose ls
docker network ls

# 3. Inspecionar o container do Cobalt (substitua <NOME_CONTAINER_COBALT> pelo nome real)
docker inspect <NOME_CONTAINER_COBALT> --format 'Image={{.Config.Image}} Networks={{json .NetworkSettings.Networks}} Labels={{json .Config.Labels}}'

# 4. Validar resposta da API do Cobalt localmente (ajuste porta/host conforme inspect)
curl -sS http://127.0.0.1:9000/ | python3 -m json.tool

# 5. Verificar proxy reverso existente e portas em uso
ss -tulpn | grep -E ':80|:443|:8080|:9000' || true
systemctl status nginx caddy traefik --no-pager 2>/dev/null || true

# 6. Verificar se há Watchtower em execução (não remover outros serviços sem autorização)
docker ps --filter "ancestor=containrrr/watchtower" --format '{{.Names}} {{.Status}}'
```

> **Importante:** Se o Cobalt já estiver rodando com Watchtower apontando para ele, não deixe dois atualizadores concorrentes para o mesmo container. Documente e decida antes da ativação do timer do DropLink.

---

## 2. Configuração de Ambiente (`.env`)

1. Copie o arquivo de exemplo:
   ```bash
   cp .env.example .env
   chmod 600 .env
   ```
2. Preencha obrigatoriamente:
   - `APP_URL`: URL HTTPS final do DropLink (ex.: `https://droplink.seudominio.com`).
   - `ADMIN_USERNAME` e `ADMIN_PASSWORD`: credenciais pessoais fortes (nunca deixe a senha de exemplo).
   - `SESSION_SECRET`: gere com `openssl rand -hex 32`.
   - `COOKIE_SECURE=true` (em produção com HTTPS; use `false` apenas em testes locais HTTP).
   - `COBALT_API_URL`: endereço alcançável pelos containers do DropLink (ex.: `http://host.docker.internal:9000` se o Cobalt publicar localmente no host, ou `http://cobalt:9000` com o arquivo opcional `compose.cobalt-net.yml`).
   - `COBALT_HOST_HEALTH_URL`: endereço alcançável diretamente pelo **host** para o script `ops/update-cobalt.sh` validar `GET /` (ex.: `http://127.0.0.1:9000`).
   - `COBALT_EXTERNAL_NETWORK`: nome da rede Docker do Cobalt existente (obtido em `docker network ls`), usado apenas quando você optar pelo `compose.cobalt-net.yml`.
   - `COBALT_COMPOSE_FILE` e `COBALT_SERVICE`: caminho absoluto real do compose e nome exato do serviço do Cobalt na VPS.
   - `COBALT_IMAGE_TAG`: mantenha a tag major homologada (ex.: `11`), nunca `latest`.

---

## 3. Subindo o DropLink em Paralelo ao Cobalt

O `compose.yml` do DropLink **não** redefine nem destrói o container do Cobalt. Por padrão ele utiliza apenas a rede isolada `droplink_internal` (com `host.docker.internal` mapeado para acessar portas locais do host) e monta o diretório de status do atualizador (`HOST_OPS_STATUS_DIR`) como **somente leitura** (`:ro`). Se o Cobalt estiver em outra rede Docker sem porta publicada no host, anexe `compose.cobalt-net.yml` conforme mostrado abaixo.

```bash
# Criar diretórios locais persistentes (SQLite em disco local, nunca em NFS/rede)
mkdir -p data/db data/storage data/ops-status backups
# Garantir permissão para o usuário não-root do container (UID/GID 10001)
sudo chown -R 10001:10001 data/db data/storage

# Construir imagens (incluindo bundle PWA) e iniciar API + Worker
docker compose build
docker compose up -d

# Verificar saúde
docker compose ps
curl -sS http://127.0.0.1:${APP_PORT:-8080}/healthz
curl -sS http://127.0.0.1:${APP_PORT:-8080}/readyz
```

> **Conexão via rede Docker externa do Cobalt (opcional):** caso o Cobalt esteja em outro projeto Compose e você use `COBALT_API_URL=http://cobalt:9000`, crie um arquivo `compose.cobalt-net.yml` apontando para a rede descoberta no inventário:
> ```yaml
> services:
>   droplink-api:
>     networks: [droplink_internal, cobalt_net]
>   droplink-worker:
>     networks: [droplink_internal, cobalt_net]
> networks:
>   cobalt_net:
>     name: ${COBALT_EXTERNAL_NETWORK:-cobalt_default}
>     external: true
> ```
> E suba com `docker compose -f compose.yml -f compose.cobalt-net.yml up -d`.

---

## 4. Proxy Reverso e HTTPS (Mesma Origem)

A PWA, a API `navigator.clipboard.readText()`, cookies `Secure`/`SameSite=Lax` e o **Web Share Target** exigem **HTTPS válido** e **mesma origem** entre frontend e API (`/api/*`, `/share-target`).

Além disso, o progresso em tempo real usa **Server-Sent Events (SSE)** em `/api/jobs/{id}/events`, e downloads grandes fazem streaming em `/api/jobs/{id}/files/{file_id}`. Desative buffering no proxy para essas rotas:

### Exemplo Nginx (adicionar ao vhost existente)
```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # Essencial para SSE (/api/jobs/*/events) e downloads em stream
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 600s;
    proxy_send_timeout 600s;
}
```

### Exemplo Caddy
```caddyfile
droplink.seudominio.com {
    reverse_proxy 127.0.0.1:8080 {
        flush_interval -1
    }
}
```

---

## 5. Instalar o Atualizador Automático do Cobalt (`systemd` no Host)

O socket Docker **nunca** é montado dentro dos containers do DropLink. O atualizador roda apenas no host:

```bash
chmod +x ops/*.sh

# Ajuste o caminho em ops/cobalt-update.service caso o projeto não esteja em /opt/droplink
sudo cp ops/cobalt-update.service /etc/systemd/system/
sudo cp ops/cobalt-update.timer /etc/systemd/system/

sudo systemctl daemon-reload
sudo systemctl enable --now cobalt-update.timer

# Conferir agendamento
systemctl list-timers cobalt-update.timer
```

---

## 6. Verificação Pós-Deploy (PWA e Share Target no Android)

1. Acesse `https://SEU-DOMINIO` no Chrome (Android) e faça login.
2. Instale a PWA pelo menu do navegador (**Adicionar à tela inicial** / **Instalar app**).
3. Teste o compartilhamento pelo menu do sistema Android (ex.: compartilhar um Reel público para o ícone do DropLink) e confirme que a URL chega no campo principal sem criar job antes do login.
4. Teste um download controlado de vídeo e verifique a aba **Motores** para confirmar versão de `yt-dlp`, `gallery-dl` e status do `cobalt`.
