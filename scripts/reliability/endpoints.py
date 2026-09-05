"""Local-only port configuration shared by Compose and the synthetic clients."""
import os

PORTS = {
    "GATEWAY_A": 18080, "CONTROL": 18081, "WORKER": 18082,
    "GATEWAY_B": 18083, "BASELINE": 18084, "CANDIDATE": 18085,
    "GRAFANA": 13000, "PROMETHEUS": 19090,
}


def endpoint(service):
    port = int(os.environ.get(f"AEGIS_{service}_PORT", PORTS[service]))
    if not 1024 <= port <= 65535:
        raise ValueError(f"Invalid local port for {service}")
    return f"http://127.0.0.1:{port}"
