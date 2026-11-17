#!/usr/bin/env bash
# ==============================================================================
# ops/update-worker.sh — Atualização controlada de yt-dlp e gallery-dl no worker
# - Constrói imagem separada de staging (droplink-worker:staging) sem sobrescrever
#   a imagem de produção antes dos testes e do dreno
# - Valida versões, ffmpeg e testes automatizados na imagem de staging
# - Drena jobs ativos (fail-closed) antes de promover e preserva rollback
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

load_env_file() {
  local env_file="$1"
  [[ -f "${env_file}" ]] || return 0
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line="${line%$'\r'}"
    [[ -z "${line}" || "${line}" =~ ^[[:space:]]*# ]] && continue
    [[ "${line}" == export\ * ]] && line="${line#export }"
    if [[ "${line}" =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]]; then
      local key="${BASH_REMATCH[1]}"
      local val="${BASH_REMATCH[2]}"
      if [[ "${val}" =~ ^\"(.*)\"$ ]]; then val="${BASH_REMATCH[1]}"
      elif [[ "${val}" =~ ^\'(.*)\'$ ]]; then val="${BASH_REMATCH[1]}"; fi
      if [[ -z "${!key+x}" ]]; then
        printf -v "${key}" '%s' "${val}"
        export "${key}"
      fi
    fi
  done < "${env_file}"
}

resolve_path() {
  local p="$1"
  [[ "${p}" == /* ]] && printf '%s' "${p}" || printf '%s' "${PROJECT_DIR}/${p#./}"
}

load_env_file "${PROJECT_DIR}/.env"

COMPOSE_FILE="${PROJECT_DIR}/compose.yml"
WORKER_SERVICE="${WORKER_SERVICE:-droplink-worker}"
API_SERVICE="${API_SERVICE:-droplink-api}"
DROPLINK_DB_PATH="$(resolve_path "${DROPLINK_DB_PATH:-data/db/droplink.db}")"
DRAIN_TIMEOUT="${WORKER_DRAIN_TIMEOUT_SECONDS:-180}"
LOCK_FILE="/tmp/droplink-worker-update.lock"
USE_NIGHTLY="${ALLOW_YTDLP_NIGHTLY:-false}"
MODE="apply"

for arg in "$@"; do
  case "${arg}" in
    --check) MODE="check" ;;
    --apply) MODE="apply" ;;
    --rollback) MODE="rollback" ;;
    --nightly) USE_NIGHTLY="true" ;;
    *) echo "Uso: $0 [--check|--apply|--rollback] [--nightly]" >&2; exit 2 ;;
  esac
done

exec 9>"${LOCK_FILE}"
flock -n 9 || { echo "Atualização do worker já em andamento."; exit 0; }

log() {
  printf '[%s] [worker-updater] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

if [[ "${MODE}" == "check" ]]; then
  exec "${SCRIPT_DIR}/check-engine-updates.sh"
fi

if [[ "${MODE}" == "rollback" ]]; then
  docker image inspect "droplink-rollback/worker:previous" >/dev/null 2>&1 || {
    echo "Nenhuma imagem anterior droplink-rollback/worker:previous encontrada." >&2
    exit 1
  }
  log "Executando rollback manual do worker..."
  docker tag "droplink-rollback/worker:previous" "droplink-worker:local"
  docker compose -f "${COMPOSE_FILE}" up -d --no-deps --pull never "${WORKER_SERVICE}"
  log "Rollback do worker concluído."
  exit 0
fi

# 1. Build isolated staging image without touching droplink-worker:local yet
STAGING_IMAGE="droplink-worker:staging"
log "Construindo imagem de staging (${STAGING_IMAGE})..."
docker build -f "${PROJECT_DIR}/backend/Dockerfile" -t "${STAGING_IMAGE}" "${PROJECT_DIR}/backend"

if [[ "${USE_NIGHTLY}" == "true" ]]; then
  log "Aplicando canal nightly opt-in do yt-dlp apenas na imagem de staging..."
  CID_TMP="$(docker create "${STAGING_IMAGE}")"
  docker start "${CID_TMP}" >/dev/null
  docker exec --user root "${CID_TMP}" pip install --no-cache-dir --pre -U yt-dlp
  docker commit "${CID_TMP}" "${STAGING_IMAGE}" >/dev/null
  docker rm -f "${CID_TMP}" >/dev/null
fi

# 2. Smoke & unit tests in isolated staging container
log "Executando testes de fumaça e adaptadores na imagem de staging..."
docker run --rm --entrypoint python "${STAGING_IMAGE}" -c \
  "import yt_dlp, gallery_dl, shutil; assert shutil.which('ffmpeg'); print('yt-dlp:', yt_dlp.version.__version__, 'gallery-dl:', gallery_dl.__version__)"

docker run --rm \
  -v "${PROJECT_DIR}/backend/tests:/app/tests:ro" \
  -v "${PROJECT_DIR}/config:/app/config:ro" \
  --entrypoint pytest "${STAGING_IMAGE}" \
  -q /app/tests/test_url_ssrf.py /app/tests/test_cobalt_fallback.py /app/tests/test_circuit_breaker.py || {
  log "Testes de staging falharam. Imagem de produção intacta."
  exit 1
}

# 3. Drain active worker jobs gracefully (fail-closed)
log "Aguardando dreno de jobs ativos antes de reiniciar o worker..."
deadline=$(( $(date +%s) + DRAIN_TIMEOUT ))
while [[ -f "${DROPLINK_DB_PATH}" ]]; do
  active="$(python3 - "${DROPLINK_DB_PATH}" <<'PY'
import sqlite3, sys
try:
    conn = sqlite3.connect(f"file:{sys.argv[1]}?mode=ro", uri=True, timeout=2.0)
    print(conn.execute("SELECT COUNT(*) FROM jobs WHERE status IN ('resolving','downloading','processing')").fetchone()[0])
    conn.close()
except Exception:
    print(-1)
PY
)"
  [[ "${active}" == "0" ]] && break
  if (( $(date +%s) >= deadline )); then
    log "Timeout no dreno (estado=${active}). Abortando promoção sem afetar produção."
    exit 1
  fi
  sleep 3
done

# 4. Preserve current production image & promote staging
CURRENT_IMAGE_ID="$(docker image inspect "droplink-worker:local" --format '{{.Id}}' 2>/dev/null || true)"
if [[ -n "${CURRENT_IMAGE_ID}" ]]; then
  PREV_WORKER_ID="$(docker image inspect "droplink-rollback/worker:previous" --format '{{.Id}}' 2>/dev/null || true)"
  if [[ "${PREV_WORKER_ID}" != "${CURRENT_IMAGE_ID}" ]]; then
    if [[ -n "${PREV_WORKER_ID}" ]]; then
      docker tag "droplink-rollback/worker:previous" "droplink-rollback/worker:older" || true
    fi
    docker tag "${CURRENT_IMAGE_ID}" "droplink-rollback/worker:previous"
  fi
fi

docker tag "${STAGING_IMAGE}" "droplink-worker:local"
log "Promovendo ${WORKER_SERVICE}..."
docker compose -f "${COMPOSE_FILE}" up -d --no-deps --pull never "${WORKER_SERVICE}"
sleep 4

RUNNING_ID="$(docker compose -f "${COMPOSE_FILE}" ps -q "${WORKER_SERVICE}" | head -n1 || true)"
STATE="$(docker inspect --format '{{.State.Running}}' "${RUNNING_ID}" 2>/dev/null || echo "false")"
if [[ "${STATE}" != "true" ]]; then
  log "Novo worker não permaneceu em execução! Restaurando imagem anterior..."
  exec "${BASH_SOURCE[0]}" --rollback
fi

"${SCRIPT_DIR}/check-engine-updates.sh" || true
log "Worker atualizado e verificado com sucesso (${API_SERVICE} preservado)."
