#!/usr/bin/env bash
# ==============================================================================
# ops/update-cobalt.sh — Atualizador controlado do Cobalt no host
# - Execução única via flock
# - Verifica digest da tag major homologada configurada no serviço Cobalt
# - Se digest for igual ao em execução, encerra sem restart
# - Preserva imagens anteriores, faz backup de config (0700), sinaliza manutenção
# - Aguarda dreno de jobs ativos do Cobalt (fail-closed), recria só o serviço Cobalt
# - Valida readiness (GET / + JSON + major + Image ID) em até 90s e faz rollback automático
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
      if [[ "${val}" =~ ^\"(.*)\"$ ]]; then
        val="${BASH_REMATCH[1]}"
      elif [[ "${val}" =~ ^\'(.*)\'$ ]]; then
        val="${BASH_REMATCH[1]}"
      fi
      if [[ -z "${!key+x}" ]]; then
        printf -v "${key}" '%s' "${val}"
        export "${key}"
      fi
    fi
  done < "${env_file}"
}

resolve_path() {
  local p="$1"
  if [[ "${p}" == /* ]]; then
    printf '%s' "${p}"
  else
    printf '%s' "${PROJECT_DIR}/${p#./}"
  fi
}

load_env_file "${PROJECT_DIR}/.env"

COBALT_COMPOSE_FILE="${COBALT_COMPOSE_FILE:-}"
COBALT_SERVICE="${COBALT_SERVICE:-cobalt}"
COBALT_IMAGE_REPO="${COBALT_IMAGE_REPO:-ghcr.io/imputnet/cobalt}"
COBALT_IMAGE_TAG="${COBALT_IMAGE_TAG:-11}"
COBALT_HOST_HEALTH_URL="${COBALT_HOST_HEALTH_URL:-http://127.0.0.1:9000}"
COBALT_API_KEY="${COBALT_API_KEY:-}"
COBALT_DRAIN_TIMEOUT_SECONDS="${COBALT_DRAIN_TIMEOUT_SECONDS:-120}"
COBALT_READINESS_TIMEOUT_SECONDS="${COBALT_READINESS_TIMEOUT_SECONDS:-90}"
COBALT_REJECTED_COOLDOWN_HOURS="${COBALT_REJECTED_COOLDOWN_HOURS:-24}"
COBALT_SMOKE_TEST_URL="${COBALT_SMOKE_TEST_URL:-}"
DROPLINK_DB_PATH="$(resolve_path "${DROPLINK_DB_PATH:-data/db/droplink.db}")"

STATUS_DIR="$(resolve_path "${HOST_OPS_STATUS_DIR:-data/ops-status}")"
STATUS_FILE="${STATUS_DIR}/cobalt-updater-status.json"
BACKUP_DIR="$(resolve_path "${HOST_BACKUPS_DIR:-backups}")/cobalt"
ROLLBACK_OVERRIDE="${STATUS_DIR}/cobalt-rollback.override.yml"
LOCK_FILE="${COBALT_LOCK_FILE:-/tmp/droplink-cobalt-update.lock}"
FORCE_UPDATE="${FORCE_UPDATE:-0}"

mkdir -p "${STATUS_DIR}" "${BACKUP_DIR}" "$(dirname "${LOCK_FILE}")"
chmod 0700 "${BACKUP_DIR}"

MAINTENANCE_ACTIVE=0
IN_RECREATE=0
RUNNING_DIGEST=""
RUNNING_IMAGE_ID=""
CURRENT_VERSION=""
PREV_DIGEST_SAVED=""
REJECTED_DIGEST=""
REJECTED_UNTIL=""
LAST_UPDATE_AT=""
LAST_ROLLBACK_AT=""
LATEST_MAJOR=""
ROLLBACK_TAG=""

log() {
  printf '[%s] [cobalt-updater] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

die() {
  log "ERROR: $*" >&2
  exit 1
}

# Single execution lock
exec 9>"${LOCK_FILE}"
if ! flock -n 9; then
  log "Outra execução já está em andamento. Saindo."
  exit 0
fi

for cmd in docker curl python3 flock; do
  command -v "${cmd}" >/dev/null 2>&1 || die "Comando obrigatório ausente: ${cmd}"
done

read_status_field() {
  local field="$1"
  local default_val="${2:-}"
  if [[ ! -f "${STATUS_FILE}" ]]; then
    printf '%s' "${default_val}"
    return
  fi
  python3 - "${STATUS_FILE}" "${field}" "${default_val}" <<'PY'
import json, sys
path, field, default_val = sys.argv[1], sys.argv[2], sys.argv[3]
try:
    with open(path, "r", encoding="utf-8") as f:
        data = json.load(f)
    val = data.get(field)
    if isinstance(val, bool):
        print("true" if val else "false")
    else:
        print("" if val is None else val)
except Exception:
    print(default_val)
PY
}

write_status() {
  local maintenance="$1"
  local state="$2"
  local current_digest="$3"
  local current_version="$4"
  local previous_digest="$5"
  local rejected_digest="$6"
  local rejected_until="$7"
  local last_update_at="$8"
  local last_rollback_at="$9"
  local last_error="${10}"
  local latest_major="${11:-}"

  local tmp_file
  tmp_file="$(mktemp "${STATUS_DIR}/.status.XXXXXX")"
  python3 - "${tmp_file}" "${maintenance}" "${state}" "${current_digest}" "${current_version}" \
    "${previous_digest}" "${rejected_digest}" "${rejected_until}" "${last_update_at}" \
    "${last_rollback_at}" "${last_error}" "${latest_major}" <<'PY'
import json, sys
from datetime import datetime, timezone

(
    out_path, maintenance, state, current_digest, current_version,
    previous_digest, rejected_digest, rejected_until, last_update_at,
    last_rollback_at, last_error, latest_major
) = sys.argv[1:13]

def norm(v):
    return None if v in ("", "null", "None") else v

payload = {
    "maintenance": maintenance.lower() == "true",
    "state": state,
    "current_digest": norm(current_digest),
    "current_version": norm(current_version),
    "previous_digest": norm(previous_digest),
    "rejected_digest": norm(rejected_digest),
    "rejected_until": norm(rejected_until),
    "last_check_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "last_update_at": norm(last_update_at),
    "last_rollback_at": norm(last_rollback_at),
    "last_error": norm(last_error),
    "latest_major_available": norm(latest_major),
}

with open(out_path, "w", encoding="utf-8") as f:
    json.dump(payload, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
  chmod 0644 "${tmp_file}"
  mv -f "${tmp_file}" "${STATUS_FILE}"
}

cleanup_on_exit() {
  local exit_code=$?
  if (( exit_code != 0 && MAINTENANCE_ACTIVE == 1 )); then
    log "Saída inesperada (exit=${exit_code}) durante janela de manutenção. Restaurando estado..."
    if (( IN_RECREATE == 1 )) && [[ -n "${ROLLBACK_TAG}" ]]; then
      cat > "${ROLLBACK_OVERRIDE}" <<YAML
services:
  ${COBALT_SERVICE}:
    image: ${ROLLBACK_TAG}
YAML
      docker compose -f "${COBALT_COMPOSE_FILE}" -f "${ROLLBACK_OVERRIDE}" up -d --no-deps --pull never "${COBALT_SERVICE}" >/dev/null 2>&1 || true
    fi
    write_status "false" "interrupted" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
      "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
      "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "Atualização interrompida inesperadamente (código ${exit_code})" "${LATEST_MAJOR}" || true
  fi
}
trap cleanup_on_exit EXIT

if [[ -z "${COBALT_COMPOSE_FILE}" || ! -f "${COBALT_COMPOSE_FILE}" ]]; then
  die "COBALT_COMPOSE_FILE inválido ou não encontrado: '${COBALT_COMPOSE_FILE}'"
fi

COMPOSE_JSON="$(docker compose -f "${COBALT_COMPOSE_FILE}" config --format json 2>/dev/null || true)"
[[ -n "${COMPOSE_JSON}" ]] || die "Configuração do Docker Compose do Cobalt está inconsistente."

SERVICE_IMAGE="$(python3 - "${COMPOSE_JSON}" "${COBALT_SERVICE}" "${COBALT_IMAGE_REPO}:${COBALT_IMAGE_TAG}" <<'PY'
import json, sys
raw, svc, fallback = sys.argv[1], sys.argv[2], sys.argv[3]
cfg = json.loads(raw)
services = cfg.get("services") or {}
if svc not in services:
    sys.exit(2)
print(services[svc].get("image") or fallback)
PY
)" || die "Serviço '${COBALT_SERVICE}' não encontrado em ${COBALT_COMPOSE_FILE}."

if [[ "${COBALT_IMAGE_TAG}" == "latest" || "${SERVICE_IMAGE}" == *:latest || "${SERVICE_IMAGE}" != *:* ]]; then
  die "Recusado usar tag 'latest' ou sem tag explícita ('${SERVICE_IMAGE}'). Fixe uma tag major homologada (ex.: :11)."
fi

TARGET_IMAGE="${SERVICE_IMAGE}"
EXPECTED_MAJOR="${TARGET_IMAGE##*:}"
EXPECTED_MAJOR="${EXPECTED_MAJOR%%.*}"

get_cobalt_container_id() {
  docker compose -f "${COBALT_COMPOSE_FILE}" ps -q "${COBALT_SERVICE}" 2>/dev/null | head -n1
}

get_image_digest_or_id() {
  local ref="$1"
  local digest
  digest="$(docker image inspect "${ref}" --format '{{index .RepoDigests 0}}' 2>/dev/null || true)"
  if [[ -n "${digest}" ]]; then
    printf '%s' "${digest##*@}"
  else
    docker image inspect "${ref}" --format '{{.Id}}' 2>/dev/null || true
  fi
}

check_cobalt_api() {
  local expected_major="${1:-}"
  local tmp_body
  tmp_body="$(mktemp)"
  local http_code
  http_code="$(curl -sS -m 5 -o "${tmp_body}" -w '%{http_code}' "${COBALT_HOST_HEALTH_URL%/}/" || echo "000")"
  if [[ "${http_code}" != "200" ]]; then
    rm -f "${tmp_body}"
    return 1
  fi
  local version
  version="$(python3 - "${tmp_body}" "${expected_major}" <<'PY'
import json, sys
body_path, expected_major = sys.argv[1], sys.argv[2]
try:
    with open(body_path, "r", encoding="utf-8") as f:
        data = json.load(f)
    cobalt = data.get("cobalt") or {}
    ver = cobalt.get("version") or data.get("version")
    services = cobalt.get("services") or data.get("services")
    if not ver:
        sys.exit(1)
    ver_str = str(ver)
    if expected_major.isdigit():
        ver_major = ver_str.lstrip("v").split(".")[0]
        if ver_major != expected_major:
            sys.exit(1)
    if services is not None and not isinstance(services, list):
        sys.exit(1)
    print(ver_str)
except Exception:
    sys.exit(1)
PY
)" || { rm -f "${tmp_body}"; return 1; }
  rm -f "${tmp_body}"
  printf '%s' "${version}"
}

wait_for_cobalt_readiness() {
  local expected_image_id="$1"
  local expected_major="${2:-}"
  local deadline=$(( $(date +%s) + COBALT_READINESS_TIMEOUT_SECONDS ))
  while (( $(date +%s) <= deadline )); do
    local cid running_id ver
    cid="$(get_cobalt_container_id)"
    running_id="$(docker inspect --format '{{.Image}}' "${cid}" 2>/dev/null || true)"
    if [[ -n "${cid}" && "${running_id}" == "${expected_image_id}" ]]; then
      if ver="$(check_cobalt_api "${expected_major}" 2>/dev/null)"; then
        printf '%s' "${ver}"
        return 0
      fi
    fi
    sleep 3
  done
  return 1
}

run_optional_smoke_test() {
  if [[ -z "${COBALT_SMOKE_TEST_URL}" ]]; then
    return 0
  fi
  local auth_header=()
  if [[ -n "${COBALT_API_KEY}" ]]; then
    auth_header=(-H "Authorization: Api-Key ${COBALT_API_KEY}")
  fi
  local resp_file
  resp_file="$(mktemp)"
  local code
  code="$(curl -sS -m 20 -o "${resp_file}" -w '%{http_code}' \
    -X POST "${COBALT_HOST_HEALTH_URL%/}/" \
    -H "Accept: application/json" \
    -H "Content-Type: application/json" \
    "${auth_header[@]}" \
    -d "{\"url\": \"${COBALT_SMOKE_TEST_URL}\"}" || echo "000")"
  if [[ "${code}" == "000" || "${code}" =~ ^5 ]]; then
    rm -f "${resp_file}"
    return 1
  fi
  python3 - "${resp_file}" <<'PY' || { rm -f "${resp_file}"; return 1; }
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
if "status" not in data:
    sys.exit(1)
PY
  rm -f "${resp_file}"
  return 0
}

check_active_cobalt_jobs() {
  if [[ ! -f "${DROPLINK_DB_PATH}" ]]; then
    printf '0'
    return 0
  fi
  python3 - "${DROPLINK_DB_PATH}" <<'PY'
import sqlite3, sys
db_path = sys.argv[1]
try:
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=2.0)
    cur = conn.execute(
        "SELECT COUNT(*) FROM jobs WHERE status IN ('resolving','downloading','processing') AND current_engine = 'cobalt'"
    )
    print(cur.fetchone()[0])
    conn.close()
except Exception:
    # Fail closed: treat query errors as busy so updates defer safely
    print(-1)
PY
}

check_latest_major_github() {
  curl -sS -m 5 -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/imputnet/cobalt/releases/latest" 2>/dev/null \
    | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("tag_name",""))' 2>/dev/null || true
}

PREV_STATE="$(read_status_field state idle)"
PREV_MAINTENANCE="$(read_status_field maintenance false)"
PREV_DIGEST_SAVED="$(read_status_field previous_digest)"
REJECTED_DIGEST="$(read_status_field rejected_digest)"
REJECTED_UNTIL="$(read_status_field rejected_until)"
LAST_UPDATE_AT="$(read_status_field last_update_at)"
LAST_ROLLBACK_AT="$(read_status_field last_rollback_at)"
PREV_ERROR="$(read_status_field last_error)"

CONTAINER_ID="$(get_cobalt_container_id)"
[[ -n "${CONTAINER_ID}" ]] || die "Container do serviço '${COBALT_SERVICE}' não encontrado em ${COBALT_COMPOSE_FILE}."

RUNNING_IMAGE_ID="$(docker inspect --format '{{.Image}}' "${CONTAINER_ID}")"
RUNNING_DIGEST="$(get_image_digest_or_id "${RUNNING_IMAGE_ID}")"
CURRENT_VERSION="$(check_cobalt_api "" 2>/dev/null || echo "")"
LATEST_MAJOR="$(check_latest_major_github)"

log "Verificando imagem ${TARGET_IMAGE} (digest atual: ${RUNNING_DIGEST})..."

if ! docker compose -f "${COBALT_COMPOSE_FILE}" pull "${COBALT_SERVICE}"; then
  write_status "${PREV_MAINTENANCE}" "pull_failed" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
    "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
    "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "Falha ao baixar imagem ${TARGET_IMAGE}" "${LATEST_MAJOR}"
  die "Falha no pull de ${TARGET_IMAGE}"
fi

PULLED_IMAGE_ID="$(docker image inspect --format '{{.Id}}' "${TARGET_IMAGE}" 2>/dev/null || true)"
PULLED_DIGEST="$(get_image_digest_or_id "${TARGET_IMAGE}")"

if [[ -n "${PULLED_IMAGE_ID}" && "${RUNNING_IMAGE_ID}" == "${PULLED_IMAGE_ID}" ]]; then
  log "Digest inalterado (${RUNNING_DIGEST}). Nenhum restart necessário."
  if [[ "${PREV_STATE}" == "failed_rollback" ]]; then
    write_status "true" "failed_rollback" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
      "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
      "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "${PREV_ERROR}" "${LATEST_MAJOR}"
  else
    write_status "false" "idle" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
      "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
      "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "" "${LATEST_MAJOR}"
  fi
  exit 0
fi

# Block previously rejected digest until a new digest arrives or FORCE_UPDATE=1
if [[ "${FORCE_UPDATE}" != "1" && -n "${REJECTED_DIGEST}" && "${PULLED_DIGEST}" == "${REJECTED_DIGEST}" ]]; then
  log "Digest ${PULLED_DIGEST} foi rejeitado anteriormente. Aguardando novo digest ou FORCE_UPDATE=1."
  if [[ "${PREV_STATE}" == "failed_rollback" ]]; then
    write_status "true" "failed_rollback" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
      "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
      "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "${PREV_ERROR}" "${LATEST_MAJOR}"
  else
    write_status "false" "cooldown_skipped" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
      "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
      "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "Digest rejeitado aguarda nova publicação ou liberação manual" "${LATEST_MAJOR}"
  fi
  exit 0
fi

# Signal maintenance & drain active Cobalt jobs (fail-closed on DB errors)
log "Nova imagem detectada (${PULLED_DIGEST}). Sinalizando manutenção e aguardando dreno..."
MAINTENANCE_ACTIVE=1
write_status "true" "draining" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
  "${RUNNING_DIGEST}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
  "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "" "${LATEST_MAJOR}"

drain_deadline=$(( $(date +%s) + COBALT_DRAIN_TIMEOUT_SECONDS ))
while true; do
  active_jobs="$(check_active_cobalt_jobs)"
  if [[ "${active_jobs}" == "0" ]]; then
    break
  fi
  if (( $(date +%s) >= drain_deadline )); then
    log "Timeout aguardando dreno (${active_jobs} jobs/estado ocupado). Adiando atualização."
    MAINTENANCE_ACTIVE=0
    write_status "false" "deferred_busy" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
      "${PREV_DIGEST_SAVED}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
      "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "Adiamento: jobs Cobalt ativos ou consulta indisponível durante janela de dreno" "${LATEST_MAJOR}"
    exit 0
  fi
  sleep 3
done

# Preserve previous healthy images AFTER drain, skipping rotation if previous already matches running
SHORT_ID="${RUNNING_IMAGE_ID#sha256:}"
SHORT_ID="${SHORT_ID:0:12}"
ROLLBACK_TAG="droplink-rollback/cobalt:id-${SHORT_ID}"
docker tag "${RUNNING_IMAGE_ID}" "${ROLLBACK_TAG}"

PREV_LOCAL_ID="$(docker image inspect "droplink-rollback/cobalt:previous" --format '{{.Id}}' 2>/dev/null || true)"
if [[ "${PREV_LOCAL_ID}" != "${RUNNING_IMAGE_ID}" ]]; then
  if [[ -n "${PREV_LOCAL_ID}" ]]; then
    docker tag "droplink-rollback/cobalt:previous" "droplink-rollback/cobalt:older" || true
  fi
  docker tag "${RUNNING_IMAGE_ID}" "droplink-rollback/cobalt:previous"
fi

# Backup compose & adjacent env files with restrictive permissions
TS="$(date -u +'%Y%m%dT%H%M%SZ')"
COMPOSE_DIR="$(dirname "${COBALT_COMPOSE_FILE}")"
mkdir -p "${BACKUP_DIR}/${TS}"
chmod 0700 "${BACKUP_DIR}/${TS}"
cp -a "${COBALT_COMPOSE_FILE}" "${BACKUP_DIR}/${TS}/"
if [[ -f "${COMPOSE_DIR}/.env" ]]; then
  cp -a "${COMPOSE_DIR}/.env" "${BACKUP_DIR}/${TS}/"
  chmod 0600 "${BACKUP_DIR}/${TS}/.env"
fi

# Recreate ONLY Cobalt service
log "Recriando apenas o serviço '${COBALT_SERVICE}'..."
IN_RECREATE=1
write_status "true" "recreating" "${RUNNING_DIGEST}" "${CURRENT_VERSION}" \
  "${RUNNING_DIGEST}" "${REJECTED_DIGEST}" "${REJECTED_UNTIL}" \
  "${LAST_UPDATE_AT}" "${LAST_ROLLBACK_AT}" "" "${LATEST_MAJOR}"

if docker compose -f "${COBALT_COMPOSE_FILE}" up -d --no-deps --pull never "${COBALT_SERVICE}"; then
  if NEW_VERSION="$(wait_for_cobalt_readiness "${PULLED_IMAGE_ID}" "${EXPECTED_MAJOR}")" && run_optional_smoke_test; then
    NOW_UTC="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    MAINTENANCE_ACTIVE=0
    IN_RECREATE=0
    log "Atualização concluída com sucesso! Versão: ${NEW_VERSION} (${PULLED_DIGEST})"
    write_status "false" "idle" "${PULLED_DIGEST}" "${NEW_VERSION}" \
      "${RUNNING_DIGEST}" "" "" "${NOW_UTC}" "${LAST_ROLLBACK_AT}" "" "${LATEST_MAJOR}"
    exit 0
  fi
fi

# Rollback flow
log "FALHA na atualização/verificação do Cobalt! Iniciando rollback automático para ${ROLLBACK_TAG}..."
cat > "${ROLLBACK_OVERRIDE}" <<YAML
services:
  ${COBALT_SERVICE}:
    image: ${ROLLBACK_TAG}
YAML

COOLDOWN_UNTIL="$(python3 - "${COBALT_REJECTED_COOLDOWN_HOURS}" <<'PY'
from datetime import datetime, timedelta, timezone
import sys
try:
    hrs = max(1, int(sys.argv[1]))
except Exception:
    hrs = 24
print((datetime.now(timezone.utc) + timedelta(hours=hrs)).strftime("%Y-%m-%dT%H:%M:%SZ"))
PY
)"
NOW_UTC="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

docker compose -f "${COBALT_COMPOSE_FILE}" -f "${ROLLBACK_OVERRIDE}" up -d --no-deps --pull never "${COBALT_SERVICE}" || true

if RESTORED_VERSION="$(wait_for_cobalt_readiness "${RUNNING_IMAGE_ID}" "")"; then
  MAINTENANCE_ACTIVE=0
  IN_RECREATE=0
  log "Rollback concluído com sucesso! Versão restaurada: ${RESTORED_VERSION} (${RUNNING_DIGEST})"
  write_status "false" "rolled_back" "${RUNNING_DIGEST}" "${RESTORED_VERSION}" \
    "${PREV_DIGEST_SAVED}" "${PULLED_DIGEST}" "${COOLDOWN_UNTIL}" \
    "${LAST_UPDATE_AT}" "${NOW_UTC}" "Rollback automático: imagem ${PULLED_DIGEST} falhou na verificação de saúde" "${LATEST_MAJOR}"
  exit 1
else
  MAINTENANCE_ACTIVE=0
  IN_RECREATE=0
  log "ALERTA CRÍTICO: Rollback do Cobalt também falhou no readiness check! Mantendo outros motores ativos."
  write_status "true" "failed_rollback" "${RUNNING_DIGEST}" "" \
    "${PREV_DIGEST_SAVED}" "${PULLED_DIGEST}" "${COOLDOWN_UNTIL}" \
    "${LAST_UPDATE_AT}" "${NOW_UTC}" "CRÍTICO: falha no Cobalt após rollback; outros motores permanecem operacionais" "${LATEST_MAJOR}"
  exit 2
fi
