#!/usr/bin/env bash
# ==============================================================================
# ops/check-engine-updates.sh — Verifica releases estáveis de yt-dlp e gallery-dl
# Consulta fontes oficiais (GitHub/PyPI/Codeberg) sem executar pip install -U
# em containers de produção.
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

STATUS_DIR="$(resolve_path "${HOST_OPS_STATUS_DIR:-data/ops-status}")"
OUT_FILE="${STATUS_DIR}/engine-updates.json"
mkdir -p "${STATUS_DIR}"

for cmd in curl python3; do
  command -v "${cmd}" >/dev/null 2>&1 || { echo "Ausente: ${cmd}" >&2; exit 1; }
done

get_installed_versions() {
  if command -v docker >/dev/null 2>&1 && docker compose -f "${PROJECT_DIR}/compose.yml" ps -q droplink-worker >/dev/null 2>&1; then
    local wid
    wid="$(docker compose -f "${PROJECT_DIR}/compose.yml" ps -q droplink-worker | head -n1 || true)"
    if [[ -n "${wid}" ]]; then
      docker exec "${wid}" python -c "import yt_dlp, gallery_dl; print(yt_dlp.version.__version__, gallery_dl.__version__)" 2>/dev/null || echo "unknown unknown"
      return
    fi
  fi
  local yv gv
  yv="$(yt-dlp --version 2>/dev/null || echo "unknown")"
  gv="$(gallery-dl --version 2>/dev/null || echo "unknown")"
  printf '%s %s\n' "${yv}" "${gv}"
}

read -r INSTALLED_YTDLP INSTALLED_GALLERYDL <<<"$(get_installed_versions)"

TMP_FILE="$(mktemp "${STATUS_DIR}/.engine-updates.XXXXXX")"

python3 - "${TMP_FILE}" "${INSTALLED_YTDLP}" "${INSTALLED_GALLERYDL}" <<'PY'
import json, re, sys, urllib.request
from datetime import datetime, timezone

out_path, cur_ytdlp, cur_gallery = sys.argv[1], sys.argv[2], sys.argv[3]

def parse_ver(v: str):
    try:
        from packaging.version import Version
        return Version(v)
    except Exception:
        return tuple(int(x) for x in re.findall(r"\d+", v))

def is_newer(installed: str, latest: str | None) -> bool:
    if not latest or installed in ("", "unknown"):
        return False
    return parse_ver(latest) > parse_ver(installed)

def fetch_json(url):
    req = urllib.request.Request(
        url,
        headers={"User-Agent": "DropLink-Engine-Checker/1.0", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        return json.loads(resp.read().decode("utf-8"))

def safe_latest(fn):
    try:
        return fn(), None
    except Exception as exc:
        return None, str(exc)

ytdlp_latest, ytdlp_err = safe_latest(
    lambda: fetch_json("https://pypi.org/pypi/yt-dlp/json")["info"]["version"]
)
gallery_latest, gallery_err = safe_latest(
    lambda: fetch_json("https://pypi.org/pypi/gallery-dl/json")["info"]["version"]
)
cobalt_latest, cobalt_err = safe_latest(
    lambda: fetch_json("https://api.github.com/repos/imputnet/cobalt/releases/latest").get("tag_name")
)

payload = {
    "checked_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "yt_dlp": {
        "installed": cur_ytdlp,
        "latest_stable": ytdlp_latest,
        "update_available": is_newer(cur_ytdlp, ytdlp_latest),
        "source": "https://pypi.org/project/yt-dlp/",
        "error": ytdlp_err,
    },
    "gallery_dl": {
        "installed": cur_gallery,
        "latest_stable": gallery_latest,
        "update_available": is_newer(cur_gallery, gallery_latest),
        "source": "https://pypi.org/project/gallery-dl/ (espelho de mikf/gallery-dl no GitHub/Codeberg)",
        "error": gallery_err,
    },
    "cobalt": {
        "latest_release_tag": cobalt_latest,
        "source": "https://github.com/imputnet/cobalt/releases",
        "error": cobalt_err,
    },
}

with open(out_path, "w", encoding="utf-8") as f:
    json.dump(payload, f, indent=2, ensure_ascii=False)
    f.write("\n")

print(json.dumps(payload, indent=2, ensure_ascii=False))
PY

chmod 0644 "${TMP_FILE}"
mv -f "${TMP_FILE}" "${OUT_FILE}"
