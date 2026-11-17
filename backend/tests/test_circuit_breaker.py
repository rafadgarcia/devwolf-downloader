from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import Settings
from app.db import get_connection
from app.engines.router import EngineCircuitBreaker


def test_circuit_breaker_opens_on_3_infra_failures_and_ignores_content_errors(
    db_conn,
    test_settings: Settings,
) -> None:
    breaker = EngineCircuitBreaker(test_settings)

    # Non-infra failure (e.g. content unavailable) does NOT trip breaker
    for _ in range(5):
        breaker.record_failure(db_conn, "cobalt", is_infra_failure=False, error_message="Mídia removida")
    allowed, _ = breaker.can_use_engine(db_conn, "cobalt")
    assert allowed is True

    # 3 consecutive infra failures open the circuit breaker
    for i in range(3):
        breaker.record_failure(db_conn, "cobalt", is_infra_failure=True, error_message=f"HTTP 502 #{i}")

    allowed_after, reason = breaker.can_use_engine(db_conn, "cobalt")
    assert allowed_after is False
    assert reason is not None

    row = db_conn.execute("SELECT circuit_state, consecutive_failures FROM engine_status WHERE engine = 'cobalt'").fetchone()
    assert row["circuit_state"] == "open"
    assert int(row["consecutive_failures"]) == 3

    # Recording success resets circuit breaker
    breaker.record_success(db_conn, "cobalt", "11.1.0")
    allowed_reset, _ = breaker.can_use_engine(db_conn, "cobalt")
    assert allowed_reset is True


def test_engines_endpoint_reflects_cobalt_updater_maintenance(
    tmp_path: Path,
    test_settings: Settings,
) -> None:
    status_file = tmp_path / "cobalt-status.json"
    status_file.write_text(
        json.dumps(
            {
                "maintenance": True,
                "state": "updating",
                "current_digest": "sha256:abc",
                "current_version": "11.0.0",
            }
        ),
        encoding="utf-8",
    )

    cfg = test_settings.model_copy(
        update={
            "cobalt_api_url": "http://cobalt:9000",
            "cobalt_status_file": str(status_file),
        }
    )

    from app.main import create_app

    app = create_app(cfg)
    with TestClient(app) as client:
        client.post("/api/auth/login", json={"username": "admin", "password": "StrongTestPassword!99"})
        resp = client.get("/api/engines")
        assert resp.status_code == 200
        engines = {e["engine"]: e for e in resp.json()["engines"]}
        assert engines["cobalt"]["health"] == "maintenance"
        assert engines["cobalt"]["maintenance_mode"] is True
        assert engines["cobalt"]["updater_status"]["state"] == "updating"
        assert "disclaimer" in resp.json()
