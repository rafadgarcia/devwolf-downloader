from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from app.engines.base import EngineError, ErrorCategory
from app.security.file_guard import (
    FileSecurityError,
    compute_sha256,
    detect_mime_from_file,
    ensure_within_dir,
    sanitize_filename,
)


@dataclass(frozen=True)
class MediaProbeInfo:
    format_name: str
    video_codec: Optional[str]
    audio_codec: Optional[str]
    width: Optional[int]
    height: Optional[int]
    pix_fmt: Optional[str]
    duration_seconds: Optional[float]
    bit_rate: Optional[int]
    is_mp4_container: bool
    is_whatsapp_status_compliant: bool


@dataclass(frozen=True)
class ProcessedFileEntry:
    path: Path
    filename: str
    mime_type: str
    size_bytes: int
    sha256: str
    duration_seconds: Optional[float] = None
    is_original: bool = False
    segment_index: Optional[int] = None


@dataclass(frozen=True)
class ProcessedMediaOutput:
    files: list[ProcessedFileEntry]
    was_reencoded: bool
    was_remuxed: bool
    segments_created: int
    probe: MediaProbeInfo


def resolve_binary(bin_name: str, *, sibling_of: Optional[str] = None) -> Optional[str]:
    found = shutil.which(bin_name)
    if found:
        return found
    if sibling_of:
        sib = shutil.which(sibling_of)
        if sib:
            sib_path = Path(sib).parent / bin_name
            if sib_path.exists():
                return str(sib_path)
            sib_exe = Path(sib).parent / f"{bin_name}.exe"
            if sib_exe.exists():
                return str(sib_exe)
    return None


def probe_media(
    file_path: Path,
    *,
    ffprobe_bin: str = "ffprobe",
    ffmpeg_bin: str = "ffmpeg",
) -> MediaProbeInfo:
    ffprobe_path = resolve_binary(ffprobe_bin, sibling_of=ffmpeg_bin)
    if not ffprobe_path:
        raise EngineError(
            "ffprobe não encontrado no servidor para inspeção de mídia.",
            category=ErrorCategory.RECOVERABLE,
            code="ffprobe_missing",
        )

    cmd = [
        ffprobe_path,
        "-v",
        "error",
        "-print_format",
        "json",
        "-show_format",
        "-show_streams",
        str(file_path),
    ]
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        shell=False,
        check=False,
    )
    if proc.returncode != 0:
        raise EngineError(
            "Falha ao inspecionar metadados do arquivo de mídia.",
            category=ErrorCategory.RECOVERABLE,
            code="ffprobe_failed",
        )

    try:
        data = json.loads(proc.stdout or "{}")
    except ValueError as exc:
        raise EngineError(
            "Resposta inválida do ffprobe.",
            category=ErrorCategory.RECOVERABLE,
            code="ffprobe_invalid_json",
        ) from exc

    fmt = data.get("format") or {}
    streams = data.get("streams") or []

    format_name = str(fmt.get("format_name") or "")
    duration_val: Optional[float] = None
    if fmt.get("duration") is not None:
        try:
            duration_val = float(fmt["duration"])
        except (TypeError, ValueError):
            duration_val = None

    bit_rate_val: Optional[int] = None
    if fmt.get("bit_rate") is not None:
        try:
            bit_rate_val = int(float(fmt["bit_rate"]))
        except (TypeError, ValueError):
            bit_rate_val = None

    video_codec: Optional[str] = None
    audio_codec: Optional[str] = None
    width: Optional[int] = None
    height: Optional[int] = None
    pix_fmt: Optional[str] = None

    for stream in streams:
        stype = stream.get("codec_type")
        if stype == "video" and video_codec is None:
            video_codec = str(stream.get("codec_name") or "").lower() or None
            pix_fmt = str(stream.get("pix_fmt") or "").lower() or None
            if stream.get("width") is not None:
                width = int(stream["width"])
            if stream.get("height") is not None:
                height = int(stream["height"])
            if duration_val is None and stream.get("duration") is not None:
                try:
                    duration_val = float(stream["duration"])
                except (TypeError, ValueError):
                    pass
        elif stype == "audio" and audio_codec is None:
            audio_codec = str(stream.get("codec_name") or "").lower() or None

    is_mp4 = ("mp4" in [p.strip().lower() for p in format_name.split(",")]) and (
        file_path.suffix.lower() == ".mp4"
    )
    compliant = (
        is_mp4
        and video_codec == "h264"
        and (audio_codec is None or audio_codec == "aac")
        and (pix_fmt is None or pix_fmt == "yuv420p")
    )
    return MediaProbeInfo(
        format_name=format_name,
        video_codec=video_codec,
        audio_codec=audio_codec,
        width=width,
        height=height,
        pix_fmt=pix_fmt,
        duration_seconds=duration_val,
        bit_rate=bit_rate_val,
        is_mp4_container=is_mp4,
        is_whatsapp_status_compliant=compliant,
    )


def _run_ffmpeg(cmd: list[str], *, timeout_seconds: int, error_msg: str) -> None:
    proc = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout_seconds,
        shell=False,
        check=False,
    )
    if proc.returncode != 0:
        raise EngineError(
            error_msg,
            category=ErrorCategory.RECOVERABLE,
            code="ffmpeg_processing_failed",
        )


def _build_processed_entry(
    file_path: Path,
    workspace: Path,
    *,
    filename: str,
    ffprobe_bin: str,
    ffmpeg_bin: str,
    max_size_bytes: int,
    is_original: bool = False,
    segment_index: Optional[int] = None,
) -> ProcessedFileEntry:
    safe_path = ensure_within_dir(workspace, file_path)
    if not safe_path.is_file() or safe_path.stat().st_size == 0:
        raise EngineError(
            "Arquivo processado pelo FFmpeg está vazio ou ausente.",
            category=ErrorCategory.RECOVERABLE,
            code="empty_processed_output",
        )
    size_bytes = safe_path.stat().st_size
    if size_bytes > max_size_bytes:
        raise EngineError(
            "Arquivo processado excede o tamanho máximo permitido.",
            category=ErrorCategory.TERMINAL,
            code="file_too_large",
        )
    try:
        mime = detect_mime_from_file(safe_path)
    except FileSecurityError as exc:
        raise EngineError(
            str(exc),
            category=ErrorCategory.RECOVERABLE,
            code="invalid_media_file",
        ) from exc

    duration_seconds: Optional[float] = None
    try:
        duration_seconds = probe_media(safe_path, ffprobe_bin=ffprobe_bin, ffmpeg_bin=ffmpeg_bin).duration_seconds
    except EngineError:
        pass

    return ProcessedFileEntry(
        path=safe_path,
        filename=filename,
        mime_type=mime,
        size_bytes=size_bytes,
        sha256=compute_sha256(safe_path),
        duration_seconds=duration_seconds,
        is_original=is_original,
        segment_index=segment_index,
    )


def process_media_for_status(
    input_path: Path,
    workspace: Path,
    *,
    preferred_filename: Optional[str] = None,
    status_mode: bool = False,
    segment_duration: Optional[int] = None,
    keep_original: bool = False,
    ffmpeg_bin: str = "ffmpeg",
    ffprobe_bin: str = "ffprobe",
    max_size_bytes: int = 500 * 1024 * 1024,
    timeout_seconds: int = 300,
) -> ProcessedMediaOutput:
    """Apply WhatsApp Status Mode compatibility (H.264/AAC MP4) and optional FFmpeg segment splitting."""
    safe_input = ensure_within_dir(workspace, input_path)
    ffmpeg_path = resolve_binary(ffmpeg_bin, sibling_of=ffprobe_bin)
    if not ffmpeg_path:
        raise EngineError(
            "FFmpeg não encontrado no servidor para processar Modo Status.",
            category=ErrorCategory.RECOVERABLE,
            code="ffmpeg_missing",
        )

    probe = probe_media(safe_input, ffprobe_bin=ffprobe_bin, ffmpeg_bin=ffmpeg_bin)
    base_name = preferred_filename or safe_input.name
    clean_stem = sanitize_filename(
        Path(base_name).stem,
        default_stem="devwolf_media",
        default_ext="",
    )
    orig_ext = safe_input.suffix.lower() or ".mp4"
    has_audio = probe.audio_codec is not None

    was_reencoded = False
    was_remuxed = False
    working_source = safe_input
    status_intermediate: Optional[Path] = None

    original_backup_path: Optional[Path] = None
    if keep_original:
        orig_name = sanitize_filename(
            f"{clean_stem}_original{orig_ext}",
            default_stem="devwolf_original",
            default_ext=orig_ext,
        )
        original_backup_path = workspace / f"_orig_{orig_name}"
        shutil.copy2(safe_input, original_backup_path)

    # Step 1: Ensure H.264 + AAC MP4 compatibility when status_mode is enabled (or when segmenting non-MP4)
    if status_mode or (segment_duration and not probe.is_whatsapp_status_compliant):
        status_target = workspace / f"{clean_stem}_status.mp4"
        if status_target == safe_input:
            status_target = workspace / f"{clean_stem}_status_out.mp4"

        if probe.is_whatsapp_status_compliant:
            if not segment_duration:
                # Smart copy without recompression
                cmd = [
                    ffmpeg_path,
                    "-y",
                    "-i",
                    str(safe_input),
                    "-c",
                    "copy",
                    "-movflags",
                    "+faststart",
                    str(status_target),
                ]
                _run_ffmpeg(
                    cmd,
                    timeout_seconds=timeout_seconds,
                    error_msg="Falha ao remuxar vídeo compatível para Modo Status.",
                )
                working_source = status_target
                status_intermediate = status_target
                was_remuxed = True
        else:
            # Transcode incompatible container/codec to H.264 + AAC MP4 preserving aspect ratio
            cmd = [
                ffmpeg_path,
                "-y",
                "-i",
                str(safe_input),
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-pix_fmt",
                "yuv420p",
                "-vf",
                "scale=trunc(iw/2)*2:trunc(ih/2)*2",
            ]
            if segment_duration and segment_duration > 0:
                cmd.extend(["-force_key_frames", f"expr:gte(t,n_forced*{segment_duration})"])
            if has_audio:
                cmd.extend(["-c:a", "aac", "-b:a", "128k"])
            else:
                cmd.append("-an")
            cmd.extend(["-movflags", "+faststart", str(status_target)])
            _run_ffmpeg(
                cmd,
                timeout_seconds=timeout_seconds,
                error_msg="Falha ao converter vídeo para formato compatível com WhatsApp Status.",
            )
            working_source = status_target
            status_intermediate = status_target
            was_reencoded = True

    entries: list[ProcessedFileEntry] = []
    segments_created = 0

    # Step 2: Optional video segment splitting into numbered clips (_part01.mp4, _part02.mp4, ...)
    if segment_duration is not None and segment_duration > 0:
        seg_template = workspace / f"{clean_stem}_part%02d.mp4"
        copy_seg_cmd = [
            ffmpeg_path,
            "-y",
            "-i",
            str(working_source),
            "-map",
            "0",
            "-c",
            "copy",
            "-f",
            "segment",
            "-segment_time",
            str(segment_duration),
            "-reset_timestamps",
            "1",
            "-segment_start_number",
            "1",
            "-movflags",
            "+faststart",
            str(seg_template),
        ]
        _run_ffmpeg(
            copy_seg_cmd,
            timeout_seconds=timeout_seconds,
            error_msg="Falha ao dividir vídeo em segmentos para Status.",
        )
        segments = sorted(workspace.glob(f"{clean_stem}_part*.mp4"))

        # If source lacked keyframes at segment intervals and produced only 1 clip for a longer video,
        # re-encode with forced keyframes so segments split cleanly on boundaries.
        src_duration = probe.duration_seconds or 0.0
        if len(segments) <= 1 and src_duration > float(segment_duration) * 1.25:
            for stale in segments:
                stale.unlink(missing_ok=True)
            reseg_cmd = [
                ffmpeg_path,
                "-y",
                "-i",
                str(working_source),
                "-map",
                "0",
                "-c:v",
                "libx264",
                "-preset",
                "veryfast",
                "-crf",
                "23",
                "-pix_fmt",
                "yuv420p",
                "-vf",
                "scale=trunc(iw/2)*2:trunc(ih/2)*2",
                "-force_key_frames",
                f"expr:gte(t,n_forced*{segment_duration})",
            ]
            if has_audio:
                reseg_cmd.extend(["-c:a", "aac", "-b:a", "128k"])
            else:
                reseg_cmd.append("-an")
            reseg_cmd.extend(
                [
                    "-f",
                    "segment",
                    "-segment_time",
                    str(segment_duration),
                    "-reset_timestamps",
                    "1",
                    "-segment_start_number",
                    "1",
                    "-movflags",
                    "+faststart",
                    str(seg_template),
                ]
            )
            _run_ffmpeg(
                reseg_cmd,
                timeout_seconds=timeout_seconds,
                error_msg="Falha ao segmentar vídeo com keyframes alinhados.",
            )
            segments = sorted(workspace.glob(f"{clean_stem}_part*.mp4"))
            was_reencoded = True

        if not segments:
            raise EngineError(
                "FFmpeg não gerou segmentos de vídeo.",
                category=ErrorCategory.RECOVERABLE,
                code="segment_empty",
            )

        segments_created = len(segments)
        for idx, seg_path in enumerate(segments, start=1):
            entries.append(
                _build_processed_entry(
                    seg_path,
                    workspace,
                    filename=sanitize_filename(seg_path.name, default_stem=f"{clean_stem}_part{idx:02d}", default_ext=".mp4"),
                    ffprobe_bin=ffprobe_bin,
                    ffmpeg_bin=ffmpeg_bin,
                    max_size_bytes=max_size_bytes,
                    is_original=False,
                    segment_index=idx,
                )
            )
        if status_intermediate is not None and status_intermediate.exists():
            status_intermediate.unlink(missing_ok=True)
    else:
        final_name = sanitize_filename(
            f"{clean_stem}_status.mp4" if status_mode else f"{clean_stem}{working_source.suffix}",
            default_stem=clean_stem,
            default_ext=".mp4",
        )
        entries.append(
            _build_processed_entry(
                working_source,
                workspace,
                filename=final_name,
                ffprobe_bin=ffprobe_bin,
                ffmpeg_bin=ffmpeg_bin,
                max_size_bytes=max_size_bytes,
                is_original=False,
                segment_index=None,
            )
        )

    if keep_original and original_backup_path is not None and original_backup_path.exists():
        orig_clean_name = sanitize_filename(
            f"{clean_stem}_original{orig_ext}",
            default_stem=f"{clean_stem}_original",
            default_ext=orig_ext,
        )
        final_orig_path = workspace / orig_clean_name
        if final_orig_path.exists() and final_orig_path != original_backup_path:
            final_orig_path = workspace / f"original_{orig_clean_name}"
        shutil.move(str(original_backup_path), str(final_orig_path))
        entries.append(
            _build_processed_entry(
                final_orig_path,
                workspace,
                filename=orig_clean_name,
                ffprobe_bin=ffprobe_bin,
                ffmpeg_bin=ffmpeg_bin,
                max_size_bytes=max_size_bytes,
                is_original=True,
                segment_index=None,
            )
        )

    return ProcessedMediaOutput(
        files=entries,
        was_reencoded=was_reencoded,
        was_remuxed=was_remuxed,
        segments_created=segments_created,
        probe=probe,
    )
