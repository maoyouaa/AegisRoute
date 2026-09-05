"""Run the v2 synthetic acceptance in a fresh, isolated local Compose project."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from datetime import datetime, timezone

from endpoints import PORTS, endpoint
from transport import api, call, connection

ROOT = Path(__file__).resolve().parents[2]


def save(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def wait_for(predicate, description, seconds=120):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        try:
            if predicate():
                return
        except (OSError, AssertionError, ValueError):
            pass
        time.sleep(1)
    raise RuntimeError("Timed out: " + description)


def startup_snapshot(compose, output):
    """Keep LKG during outage, then prove a fresh process fails closed without it."""
    def get(service, path):
        client = connection(endpoint(service))
        try:
            client.request('GET', path)
            response = client.getresponse()
            return response.status, response.read().decode()
        finally:
            client.close()

    def age(service):
        status, body = get(service, '/actuator/prometheus')
        assert status == 200
        return float(next(line.split()[-1] for line in body.splitlines()
                          if line.startswith('aegis_route_snapshot_age_seconds ')))

    gateways = ('GATEWAY_A', 'GATEWAY_B')
    report = {'status': 'RUNNING', 'gateways': {g: {'ageBefore': age(g)} for g in gateways}}
    save(output / 'startup-snapshot.json', report)
    subprocess.run([*compose, 'stop', '-t', '1', 'control'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    try:
        time.sleep(3)
        for gateway in gateways:
            item = report['gateways'][gateway]
            item['ageDuringOutage'] = age(gateway)
            assert item['ageDuringOutage'] > item['ageBefore']
            item['lkgReady'] = get(gateway, '/health/ready')[0]
            item['lkgProbe'] = call(endpoint(gateway), 'startup-lkg-' + gateway, False, 'application/json')
            assert item['lkgReady'] == item['lkgProbe']['status'] == 200
        save(output / 'startup-snapshot.json', report)
        subprocess.run([*compose, 'restart', 'gateway-a', 'gateway-b'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
        for gateway in gateways:
            wait_for(lambda: get(gateway, '/health/live')[0] == 200, gateway + ' no-snapshot liveness')
            item = report['gateways'][gateway]
            item['noSnapshotLive'] = get(gateway, '/health/live')[0]
            item['noSnapshotReady'] = get(gateway, '/health/ready')[0]
            item['noSnapshotProbe'] = call(endpoint(gateway), 'startup-unavailable-' + gateway, False, 'application/json')
            assert item['noSnapshotLive'] == 200
            assert item['noSnapshotReady'] == item['noSnapshotProbe']['status'] == 503
            assert json.loads(item['noSnapshotProbe']['body'])['error']['code'] == 'ROUTE_SNAPSHOT_UNAVAILABLE'
        save(output / 'startup-snapshot.json', report)
    finally:
        subprocess.run([*compose, 'up', '-d', '--no-deps', 'control'], cwd=ROOT, check=True, stdout=subprocess.DEVNULL)
    for gateway in gateways:
        wait_for(lambda: get(gateway, '/health/ready')[0] == 200, gateway + ' snapshot recovery')
        report['gateways'][gateway]['recoveredReady'] = get(gateway, '/health/ready')[0]
    report['status'] = 'PASS'
    save(output / 'startup-snapshot.json', report)


def automatic_recovery(compose, output):
    """PID 1 exits must recover through restart policy, without docker start/up."""
    def capture(*args):
        return subprocess.check_output(args, cwd=ROOT, text=True).strip()

    def state(cid):
        return json.loads(capture("docker", "inspect", "--format", "{{json .State}}", cid))

    def restarts(cid):
        return int(capture("docker", "inspect", "--format", "{{.RestartCount}}", cid))

    before_route = api(endpoint("CONTROL"), "/internal/v2/status")["route"]["version"]
    report = {"status": "RUNNING", "routeBefore": before_route, "dependencies": {}, "probes": []}
    for service in ("postgres", "redpanda"):
        cid = capture(*compose, "ps", "-q", service)
        report["dependencies"][service] = {"containerId": cid, "restartsBefore": restarts(cid)}
    save(output / "automatic-recovery.json", report)
    for service, signal in (("postgres", "QUIT"), ("redpanda", "TERM")):
        capture("docker", "exec", "--user", "0", report["dependencies"][service]["containerId"],
                "sh", "-c", "kill -" + signal + " 1")
    for i in range(5):
        stream = i % 2 == 1
        probe = call(endpoint("GATEWAY_A"), f"dependency-recovery-{i}", stream,
                     "text/event-stream" if stream else "application/json")
        report["probes"].append(probe)
        save(output / "automatic-recovery.json", report)
        assert probe.get("status") == 200 and not probe.get("transportError"), probe
        if stream:
            assert any("[DONE]" in frame for frame in probe["frames"]), probe
    for service, item in report["dependencies"].items():
        cid = item["containerId"]
        wait_for(lambda: restarts(cid) > item["restartsBefore"], service + " automatic restart")
        wait_for(lambda: state(cid).get("Health", {}).get("Status") == "healthy", service + " health")
        item["restartsAfter"] = restarts(cid)
        item["healthAfter"] = state(cid)["Health"]["Status"]
    for service in ("CONTROL", "WORKER"):
        wait_for(lambda: api(endpoint(service), "/actuator/health")["status"] == "UP", service + " health")
    report["routeAfter"] = api(endpoint("CONTROL"), "/internal/v2/status")["route"]["version"]
    assert report["routeAfter"] >= before_route
    report.update(status="PASS", controlHealth="UP", workerHealth="UP")
    save(output / "automatic-recovery.json", report)


def bind_runtime(compose, output):
    names = subprocess.check_output(['git', 'ls-files', '-z', '--cached', '--others', '--exclude-standard'], cwd=ROOT).decode().split('\0')
    included = ('apps/', 'modules/', 'contracts/', 'deployment/', 'scripts/', '.github/', 'gradle/')
    files = []
    for name in sorted(set(names)):
        path = ROOT / name
        root_inputs = {'gradlew', 'gradlew.bat', 'gradle.properties', '.dockerignore', '.gitattributes'}
        if path.is_file() and (name in root_inputs or name.startswith(included) or name.startswith('compose') or name.endswith('.gradle.kts')):
            files.append({'path': name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    jars = {}
    for service, app in (('gateway-a', 'gateway'), ('gateway-b', 'gateway'), ('control', 'control'),
                         ('worker', 'worker'), ('baseline', 'mock-provider'), ('candidate', 'mock-provider')):
        local = hashlib.sha256((ROOT / f'apps/{app}/build/libs/application.jar').read_bytes()).hexdigest()
        running = subprocess.check_output([*compose, 'exec', '-T', service, 'sha256sum', '/app/application.jar'], cwd=ROOT).decode().split()[0]
        assert local == running, service
        jars[service] = {'app': app, 'localAndRunningJarSha256': local}
    save(output / 'runtime-binding.json', {'engineeringFiles': files, 'jars': jars,
                                         'scope': 'Source/build/config/script hashes at launch; exact local jar vs each running Java service. Documentation is recorded separately.'})


def main(args):
    if not re.fullmatch(r"aegis-[a-z0-9][a-z0-9-]{1,62}", args.project):
        raise ValueError("Use a fresh aegis-... project; never aegisroute")
    output = (ROOT / args.output).resolve()
    output.relative_to(ROOT / "build-evidence")
    if output.exists():
        raise ValueError("Evidence directory already exists; choose a new run directory")
    for kind in ("container", "volume", "network"):
        command = ["docker", "ps", "-aq"] if kind == "container" else ["docker", kind, "ls", "-q"]
        found = subprocess.check_output([*command, "--filter", f"label=com.docker.compose.project={args.project}"], text=True)
        if found.strip():
            raise ValueError(f"Project already owns {kind} resources: choose a new project")
    for service, default in PORTS.items():
        port = default + args.port_offset
        if not 1024 <= port <= 65535:
            raise ValueError("Port offset outside local unprivileged range")
        with socket.socket() as listener:
            if hasattr(socket, "SO_EXCLUSIVEADDRUSE"):
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(("127.0.0.1", port))
        os.environ[f"AEGIS_{service}_PORT"] = str(port)
    output.mkdir(parents=True)
    compose = ["docker", "compose", "-p", args.project, "-f", "compose.reliability.yml"]
    commands = []

    def execute(name, command):
        start = datetime.now(timezone.utc).isoformat()
        print(name, flush=True)
        with (output / (name + ".log")).open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, text=True)
        commands.append({"name": name, "command": list(map(str, command)), "startedUtc": start,
                         "exitCode": result.returncode})
        save(output / "commands.json", commands)
        if result.returncode:
            raise RuntimeError(f"{name} failed ({result.returncode}); inspect {name}.log")

    save(output / "parameters.json", {"project": args.project, "syntheticOnly": True,
                                      "endpoints": {name: endpoint(name) for name in PORTS},
                                      "skipBuild": args.skip_build, "benchmark": args.benchmark})
    print('Grafana: ' + endpoint('GRAFANA') + '/d/aegisroute-v01', flush=True)
    started = False
    result = {"status": "FAIL"}
    try:
        if not args.skip_build:
            wrapper = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
            execute("wrapper", [wrapper, "clean", "check", "integrationTest",
                                *[f":apps:{app}:bootJar" for app in ("gateway", "control", "worker", "mock-provider")],
                                "--max-workers=2", "--no-build-cache", "--console=plain"])
        execute("default-compose-config", ["docker", "compose", "config", "--quiet"])
        execute("reliability-compose-config", [*compose, "config", "--quiet"])
        started = True
        execute("start", [*compose, "up", "--build", "--wait"])
        for service in ("GATEWAY_A", "GATEWAY_B"):
            wait_for(lambda: api(endpoint(service), "/actuator/health/readiness")["status"] == "UP", service)
        for service in ("CONTROL", "WORKER"):
            wait_for(lambda: api(endpoint(service), "/actuator/health")["status"] == "UP", service)
        for service in ("BASELINE", "CANDIDATE"):
            wait_for(lambda: api(endpoint(service), "/internal/stats"), service)
        bind_runtime(compose, output)
        startup_snapshot(compose, output)
        execute("transport", [sys.executable, "scripts/reliability/transport.py", "--output", str(output / "transport")])
        execute("scenario", [sys.executable, "scripts/reliability/scenario.py", "--project", args.project,
                             "--output", str(output / "scenario")])
        execute("reconcile", [sys.executable, "scripts/reliability/reconcile.py", "--scenario", str(output / "scenario")])
        execute("online-convergence", [sys.executable, "scripts/reliability/verify_online_convergence.py", "--scenario", str(output / "scenario")])
        automatic_recovery(compose, output)
        if args.benchmark:
            execute("benchmark", [sys.executable, "scripts/reliability/benchmark.py", "--project", args.project,
                                  "--output", str(output / "benchmark"), "--seed", "20260905", "--count", "240",
                                  "--concurrency", "4", "--rate", "20"])
            execute("benchmark-reconcile", [sys.executable, "scripts/reliability/reconcile.py", "--scenario", str(output / "benchmark")])
            execute("benchmark-analysis", [sys.executable, "scripts/reliability/analyze_benchmark.py", "--benchmark", str(output / "benchmark")])
        result = {"status": "PASS", "syntheticOnly": True, "benchmarkRun": args.benchmark}
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        save(output / "RESULT.json", result)
        if started:
            for name, command in (("topology", [*compose, "ps", "--format", "json"]),
                                  ("services", [*compose, "logs", "--no-color"])):
                with (output / (name + ".log")).open("w", encoding="utf-8") as log:
                    subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, text=True)
            if not args.keep_running:
                try:
                    execute("stop", [*compose, "down"])  # Preserve this run's durable evidence volumes.
                except Exception as error:
                    save(output / "RESULT.json", {"status": "FAIL", "error": str(error), "acceptance": result})
                    raise
    print(json.dumps(result), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--project", required=True)
    parser.add_argument("--output", required=True, help="New directory under build-evidence/")
    parser.add_argument("--port-offset", type=int, default=0)
    parser.add_argument("--skip-build", action="store_true", help="Use jars already built by the wrapper")
    parser.add_argument("--benchmark", action="store_true", help="Also run six matched measurement rounds")
    parser.add_argument("--keep-running", action="store_true")
    main(parser.parse_args())
