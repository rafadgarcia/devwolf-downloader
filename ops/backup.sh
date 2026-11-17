#!/usr/bin/env bash
# ==============================================================================
# ops/backup.sh — Backup online consistente do SQLite (WAL) e configurações
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

DB_PATH="$(resolve_path "${DROPLINK_DB_PATH:-data/db/droplink.db}")"
BACKUP_ROOT="$(resolve_path "${HOST_BACKUPS_DIR:-backups}")"
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
TS="$(date -u +'%Y%m%dT%H%M%SZ')"
TARGET_DIR="${BACKUP_ROOT}/${TS}"

mkdir -p "${TARGET_DIR}"
chmod 0700 "${BACKUP_ROOT}" "${TARGET_DIR}"

log() {
  printf '[%s] [backup] %s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')" "$*"
}

if [[ -f "${DB_PATH}" ]]; then
  log "Executando backup online do SQLite em ${DB_PATH}..."
  python3 - "${DB_PATH}" "${TARGET_DIR}/droplink.db" <<'PY'
import sqlite3, sys
src_path, dst_path = sys.argv[1], sys.argv[2]
src = sqlite3.connect(f"file:{src_path}?mode=ro", uri=True, timeout=10.0)
dst = sqlite3.connect(dst_path)
with dst:
    src.backup(dst)
dst.close()
src.close()
PY
  chmod 0600 "${TARGET_DIR}/droplink.db"
else
  log "AVISO: Banco SQLite não encontrado em ${DB_PATH}; pulando dump do DB."
fi

mkdir -p "${TARGET_DIR}/config"
cp -a "${PROJECT_DIR}/config/routes.yaml" "${TARGET_DIR}/config/"
if [[ -f "${PROJECT_DIR}/.env" ]]; then
  cp -a "${PROJECT_DIR}/.env" "${TARGET_DIR}/dot-env.backup"
  chmod 0600 "${TARGET_DIR}/dot-env.backup"
fi
if [[ -f "${PROJECT_DIR}/compose.yml" ]]; then
  cp -a "${PROJECT_DIR}/compose.yml" "${TARGET_DIR}/"
fi
if [[ -n "${COBALT_COMPOSE_FILE:-}" && -f "${COBALT_COMPOSE_FILE}" ]]; then
  mkdir -p "${TARGET_DIR}/cobalt"
  cp -a "${COBALT_COMPOSE_FILE}" "${TARGET_DIR}/cobalt/"
fi

ARCHIVE_PATH="${BACKUP_ROOT}/droplink-backup-${TS}.tar.gz"
tar -C "${BACKUP_ROOT}" -czf "${ARCHIVE_PATH}" "${TS}"
chmod 0600 "${ARCHIVE_PATH}"
rm -rf "${TARGET_DIR}"

log "Backup criado: ${ARCHIVE_PATH}"

find "${BACKUP_ROOT}" -maxdepth 1 -name 'droplink-backup-*.tar.gz' -type f -mtime +"${KEEP_DAYS}" -delete
log "Retenção aplicada (${KEEP_DAYS} dias)."
