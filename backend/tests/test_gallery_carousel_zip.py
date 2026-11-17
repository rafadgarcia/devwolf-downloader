from __future__ import annotations

import json
import zipfile
from pathlib import Path

import pytest

from app.config import Settings
from app.engines.base import CancellationToken, EngineRequest
from app.engines.gallerydl_adapter import GalleryDlEngine

FAKE_JPG = b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00" + b"\x11" * 40
FAKE_PNG = b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR" + b"\x22" * 40


@pytest.mark.asyncio
async def test_gallery_dl_packages_carousel_into_ordered_zip(
    tmp_path: Path,
    test_settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeProc:
        def __init__(self, dl_dir: Path):
            self.returncode = 0
            self._polls = 0
            self.stderr = self

            # Write 3 carousel items + metadata json into dl_dir
            (dl_dir / "001_post.jpg").write_bytes(FAKE_JPG)
            (dl_dir / "002_post.png").write_bytes(FAKE_PNG)
            (dl_dir / "003_post.jpg").write_bytes(FAKE_JPG)
            (dl_dir / "001_post.jpg.json").write_text(
                json.dumps({"description": "Carrossel de teste", "count": 3}),
                encoding="utf-8",
            )

        def poll(self):
            return 0

        def read(self):
            return ""

        def terminate(self):
            pass

        def kill(self):
            pass

        def wait(self, timeout=None):
            return 0

    def fake_popen(cmd, **kwargs):
        dir_idx = cmd.index("--directory")
        dl_dir = Path(cmd[dir_idx + 1])
        return FakeProc(dl_dir)

    monkeypatch.setattr("app.engines.gallerydl_adapter.subprocess.Popen", fake_popen)

    adapter = GalleryDlEngine(test_settings)
    req = EngineRequest(
        job_id="g1",
        url="https://www.instagram.com/p/CAROUSEL1/",
        platform="instagram",
        route_id="instagram_post",
        format="auto",
        quality="best",
        max_size_bytes=50_000_000,
        max_duration_seconds=60,
        max_carousel_items=50,
    )

    result = await adapter.download(req, tmp_path / "ws_g1", lambda p: None, CancellationToken())
    assert result.engine == "gallery-dl"
    assert len(result.files) == 1
    archive = result.files[0]
    assert archive.is_archive is True
    assert archive.mime_type == "application/zip"
    assert archive.item_count == 3
    assert archive.missing_items == 0
    assert result.title == "Carrossel de teste"

    with zipfile.ZipFile(archive.path) as zf:
        names = zf.namelist()
        assert names == ["001_post.jpg", "002_post.png", "003_post.jpg"]
