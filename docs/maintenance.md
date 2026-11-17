# Guia de Manutenção, Atualizações e Backup — DropLink

Este documento descreve a operação rotineira do Cobalt, dos motores do worker (`yt-dlp` e `gallery-dl`), além de backup e restauração do SQLite.

---

## 1. Atualização Automática e Segura do Cobalt

O repositório original `containrrr/watchtower` foi arquivado em **17/12/2025**. Por isso, o DropLink adota o script `ops/update-cobalt.sh` acionado por `systemd timer` no host (`ops/cobalt-update.timer`, a cada 30 minutos).

### Como o fluxo funciona:
1. **Execução única (`flock`)**: impede execuções sobrepostas.
2. **Validação prévia**: recusa tag `latest` e valida `docker compose config` antes de qualquer ação.
3. **Comparação de digest**: faz `docker compose pull` da tag major homologada (ex.: `ghcr.io/imputnet/cobalt:11`) e compara o ID/digest com o container em execução. **Se forem iguais, encerra sem reiniciar.**
4. **Cooldown contra boot-loop**: se o digest baixado já foi rejeitado anteriormente por falha de boot, pula a atualização pelo período definido em `COBALT_REJECTED_COOLDOWN_HOURS` (padrão 24h).
5. **Preservação e backup**: salva tags locais `droplink-rollback/cobalt:previous` e `:older` (nunca executa `docker image prune`) e copia `compose.yml`/`.env` do Cobalt para `backups/cobalt/`.
6. **Manutenção e dreno**: publica `maintenance: true` atomicamente em `cobalt-updater-status.json` (lido em modo read-only pelo DropLink) e aguarda até `COBALT_DRAIN_TIMEOUT_SECONDS` para jobs ativos do Cobalt terminarem. Durante esse período, `yt-dlp` e `gallery-dl` continuam atendendo normalmente.
7. **Recriação isolada**: executa `docker compose up -d --no-deps --pull never <COBALT_SERVICE>` — nenhum outro container ou volume é tocado.
8. **Verificação e Rollback**: testa `GET /` (contrato JSON e versão) por até 90 segundos (`COBALT_READINESS_TIMEOUT_SECONDS`). Se falhar, cria um override temporário (`cobalt-rollback.override.yml`) apontando para `droplink-rollback/cobalt:previous`, recria o Cobalt, registra o digest rejeitado e publica o incidente no painel **Motores**.

### Comandos úteis no host:
```bash
# Executar verificação manual imediata
sudo systemctl start cobalt-update.service
journalctl -u cobalt-update.service -n 100 --no-pager

# Conferir agendamento ou ajustar intervalo (padrão *:0/30 = a cada 30 min)
systemctl list-timers cobalt-update.timer
# Para alterar o intervalo sem editar o arquivo versionado:
sudo systemctl edit cobalt-update.timer
# Adicione: [Timer] \n OnCalendar= \n OnCalendar=*:0/15

# Forçar nova tentativa ignorando bloqueio de digest rejeitado
FORCE_UPDATE=1 bash ops/update-cobalt.sh
```

> **Digest rejeitado:** quando um novo digest falha na verificação de saúde, ele fica bloqueado até que um **novo digest** seja publicado no registro ou que você libere manualmente com `FORCE_UPDATE=1` (evitando loops de reinício).

> **Atualização de Major (ex.: v11 → v12):** o painel **Motores** sinaliza quando há uma nova release major no GitHub, mas a mudança de `COBALT_IMAGE_TAG` deve ser feita manualmente após revisar breaking changes e testar em staging.

---

## 2. Atualização de `yt-dlp` e `gallery-dl` (Worker)

Em produção, os containers **não** executam `pip install -U` no boot. As versões são fixadas no build para garantir reprodutibilidade e rollback imediato.

```bash
# 1. Consultar versões estáveis mais recentes (PyPI / GitHub / Codeberg)
bash ops/check-engine-updates.sh

# Opcional: habilitar verificação diária automática no host
sudo cp ops/worker-check.service ops/worker-check.timer /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now worker-check.timer

# 2. Atualizar worker com imagem isolada de staging, testes automatizados e dreno de fila
bash ops/update-worker.sh --apply

# Opcional: usar canal nightly do yt-dlp (somente opt-in quando houver quebra recente de plataforma)
bash ops/update-worker.sh --apply --nightly

# Reverter imediatamente para a imagem anterior do worker em caso de regressão
bash ops/update-worker.sh --rollback
```

---

## 3. Backup e Restauração (SQLite WAL + Configurações)

Nunca copie o arquivo `.db` diretamente com `cp` enquanto API e worker estiverem escrevendo no modo WAL. Use sempre o script `ops/backup.sh`, que utiliza a API de backup online nativa do SQLite (`sqlite3.Connection.backup`).

```bash
# Gerar backup consistente em backups/droplink-backup-YYYYMMDDTHHMMSSZ.tar.gz
bash ops/backup.sh
```

### Agendar backup diário via cron (exemplo):
```cron
15 3 * * * /opt/droplink/ops/backup.sh >> /var/log/droplink-backup.log 2>&1
```

### Procedimento de Restauração:
1. Pare API e worker para evitar escritas concorrentes:
   ```bash
   docker compose stop droplink-api droplink-worker
   ```
2. Extraia o arquivo desejado em diretório temporário:
   ```bash
   mkdir -p /tmp/droplink-restore
   tar -xzf backups/droplink-backup-YYYYMMDDTHHMMSSZ.tar.gz -C /tmp/droplink-restore
   ```
3. Substitua o banco (removendo eventuais `-wal` e `-shm` antigos do banco substituído):
   ```bash
   rm -f data/db/droplink.db-wal data/db/droplink.db-shm
   cp /tmp/droplink-restore/*/droplink.db data/db/droplink.db
   rm -rf /tmp/droplink-restore
   ```
4. Suba os serviços novamente:
   ```bash
   docker compose up -d
   ```
