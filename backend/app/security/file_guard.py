from __future__ import annotations

import hashlib
import mimetypes
import re
import unicodedata
import zipfile
from pathlib import Path
from typing import Iterable


SAFE_FILENAME_RE = re.compile(r"[^A-Za-z0-9._-]+")

# Magic byte signatures for safe media types
_MAGIC_SIGNATURES: list[tuple[bytes, int, str]] = [
    (b"\x89PNG\r\n\x1a\n", 0, "image/png"),
    (b"\xff\xd8\xff", 0, "image/jpeg"),
    (b"GIF87a", 0, "image/gif"),
    (b"GIF89a", 0, "image/gif"),
    (b"RIFF", 0, "video/webm_or_webp"),  # further checked below
    (b"\x1a\x45\xdf\xa3", 0, "video/webm"),
    (b"ID3", 0, "audio/mpeg"),
    (b"\xff\xfb", 0, "audio/mpeg"),
    (b"\xff\xf3", 0, "audio/mpeg"),
    (b"\xff\xf2", 0, "audio/mpeg"),
    (b"OggS", 0, "audio/ogg"),
    (b"fLaC", 0, "audio/flac"),
    (b"PK\x03\x04", 0, "application/zip"),
]

ALLOWED_MIME_PREFIXES = ("video/", "audio/", "image/", "application/zip")


class FileSecurityError(ValueError):
    """Raised when a file fails safety, path traversal, symlink, or MIME validation."""


def sanitize_filename(name: str, default_stem: str = "droplink_media", default_ext: str = "") -> str:
    """Sanitize untrusted filenames/titles so they cannot cause path traversal or header injection."""
    normalized = unicodedata.normalize("NFKD", name or "")
    ascii_str = normalized.encode("ascii", "ignore").decode("ascii")
    # Strip any directory components
    base = Path(ascii_str.replace("\\", "/")).name
    stem = Path(base).stem
    ext = Path(base).suffix.lower()

    clean_stem = SAFE_FILENAME_RE.sub("_", stem).strip("._-")[:80]
    if not clean_stem:
        clean_stem = default_stem

    clean_ext = re.sub(r"[^a-z0-9.]", "", ext)[:12]
    if not clean_ext and default_ext:
        clean_ext = default_ext if default_ext.startswith(".") else f".{default_ext}"
    return f"{clean_stem}{clean_ext}"


def ensure_within_dir(base_dir: Path, target_path: Path) -> Path:
    """Prevent path traversal and symlink escapes out of base_dir."""
    base_resolved = base_dir.resolve()
    if target_path.is_symlink():
        raise FileSecurityError(f"Symlink não permitido: {target_path.name}")
    target_resolved = target_path.resolve()
    try:
        target_resolved.relative_to(base_resolved)
    except ValueError as exc:
        raise FileSecurityError("Path traversal detectado fora do diretório permitido.") from exc
    return target_resolved


def detect_mime_from_file(path: Path) -> str:
    """Identify MIME type primarily using magic bytes, falling back to safe extension."""
    if not path.is_file() or path.is_symlink():
        raise FileSecurityError("Arquivo inválido ou symlink.")

    with path.open("rb") as fh:
        header = fh.read(64)

    if len(header) == 0:
        raise FileSecurityError("Arquivo vazio rejeitado.")

    # Reject HTML/script payloads masquerading as media
    trimmed = header.lstrip().lower()
    if trimmed.startswith((b"<!doctype html", b"<html", b"<script", b"<svg", b"<?xml")):
        raise FileSecurityError("Conteúdo HTML/XML/SVG inline não permitido como mídia.")

    if len(header) >= 12 and header[4:8] == b"ftyp":
        brand = header[8:12]
        if brand in {b"M4A ", b"M4B "}:
            return "audio/mp4"
        return "video/mp4"

    if header.startswith(b"RIFF") and len(header) >= 12:
        fourcc = header[8:12]
        if fourcc == b"WEBP":
            return "image/webp"
        if fourcc == b"AVI ":
            return "video/x-msvideo"
        if fourcc == b"WAVE":
            return "audio/wav"

    for sig, offset, mime in _MAGIC_SIGNATURES:
        if len(header) >= offset + len(sig) and header[offset : offset + len(sig)] == sig:
            if mime != "video/webm_or_webp":
                return mime

    guessed, _ = mimetypes.guess_type(path.name)
    if guessed and guessed.startswith(ALLOWED_MIME_PREFIXES):
        return guessed

    raise FileSecurityError(f"Tipo de arquivo não suportado ou assinatura desconhecida: {path.name}")


def compute_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        while chunk := fh.read(65536):
            digest.update(chunk)
    return digest.hexdigest()


def create_safe_zip(
    source_files: Iterable[Path],
    zip_path: Path,
    workspace_root: Path,
) -> tuple[Path, int]:
    """Validate every file and package them into a flat, zip-slip-free ZIP archive."""
    validated: list[Path] = []
    for src in source_files:
        safe_src = ensure_within_dir(workspace_root, src)
        detect_mime_from_file(safe_src)
        validated.append(safe_src)

    if not validated:
        raise FileSecurityError("Nenhum arquivo válido para empacotar no ZIP.")

    zip_path.parent.mkdir(parents=True, exist_ok=True)
    used_names: set[str] = set()
    with zipfile.ZipFile(zip_path, mode="w", compression=zipfile.ZIP_DEFLATED) as zf:
        for idx, file_path in enumerate(validated, start=1):
            arcname = sanitize_filename(file_path.name, default_stem=f"item_{idx:03d}")
            if arcname in used_names:
                stem = Path(arcname).stem
                ext = Path(arcname).suffix
                arcname = f"{stem}_{idx:03d}{ext}"
            used_names.add(arcname)
            zf.write(file_path, arcname=arcname)

    return zip_path, len(validated)
