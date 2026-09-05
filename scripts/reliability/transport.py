"""Actual localhost HTTP/SSE clients against the isolated Compose demo; synthetic data only."""
import argparse
import http.client
import json
import time
import uuid
from pathlib import Path
from urllib.parse import urlsplit
from endpoints import endpoint


def connection(url, timeout=8):
    parts = urlsplit(url)
    if parts.hostname not in ("127.0.0.1", "localhost") or parts.scheme != "http":
        raise ValueError("This synthetic fault client only accepts localhost HTTP endpoints")
    return http.client.HTTPConnection(parts.hostname, parts.port, timeout=timeout)


def api(url, path, method="GET", body=None):
    conn = connection(url)
    try:
        conn.request(method, path, None if body is None else json.dumps(body), {"Content-Type": "application/json"})
        response = conn.getresponse()
        data = response.read()
        if response.status >= 400:
            raise AssertionError((response.status, data.decode()))
        return json.loads(data) if data else None
    finally:
        conn.close()


def configure(url, mode="normal"):
    return api(url, "/internal/faults", "POST", {"mode": mode, "failEvery": 0, "latencyMs": 5})


def call(url, request_id, stream, accept, cancel=False):
    conn = connection(url)
    started = time.perf_counter()
    result = {"requestId": request_id, "stream": stream, "accept": accept, "frames": []}
    body = {"model": "synthetic", "messages": [{"role": "user", "content": "Synthetic transport fixture"}], "stream": stream}
    try:
        conn.request("POST", "/v1/chat/completions", json.dumps(body),
                     {"Content-Type": "application/json", "Accept": accept, "X-Request-Id": request_id})
        response = conn.getresponse()
        result.update(status=response.status, contentType=response.getheader("Content-Type"))
        if stream and response.status == 200:
            while True:
                line = response.readline()
                if not line:
                    break
                text = line.decode().strip()
                if text.startswith("data:"):
                    frame = text[5:].strip()
                    result["frames"].append(frame)
                    if "firstFrameMs" not in result:
                        result["firstFrameMs"] = (time.perf_counter() - started) * 1000
                    if cancel:
                        result["clientCancelled"] = True
                        response.close()
                        break
        else:
            result["body"] = response.read().decode()
    except (OSError, http.client.HTTPException) as error:
        result["transportError"] = type(error).__name__ + ": " + str(error)
    finally:
        conn.close()
        result["elapsedMs"] = (time.perf_counter() - started) * 1000
    return result


def run(args):
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    run_id = "wire-" + uuid.uuid4().hex[:8]
    results = []
    cases = [("json", "normal", False, "application/json"),
             ("body-selects-json", "normal", False, "text/event-stream"),
             ("stream", "normal", True, "application/json"),
             ("http429", "http-429", True, "text/event-stream"),
             ("http400", "http-400", False, "application/json"),
             ("http500", "http-500", False, "application/json"),
             ("pre-token", "disconnect-before-token", True, "text/event-stream"),
             ("post-token", "disconnect-after-token", True, "text/event-stream"),
             ("malformed", "malformed", True, "text/event-stream"),
             ("empty", "empty", True, "text/event-stream"),
             ("timeout", "timeout", False, "application/json"),
             ("pre-token-timeout", "timeout", True, "text/event-stream"),
             ("total-deadline", "long-stream", True, "text/event-stream"),
             ("cancel", "long-stream", True, "text/event-stream")]
    try:
        for name, mode, stream, accept in cases:
            configure(args.baseline, mode)
            result = call(args.gateway, f"{run_id}-{name}", stream, accept, cancel=name == "cancel")
            result["case"] = name
            results.append(result)
            print(json.dumps({key: value for key, value in result.items() if key not in ("frames", "body")}), flush=True)
        time.sleep(0.5)
        baseline = api(args.baseline, "/internal/stats")
        candidate = api(args.candidate, "/internal/stats")
        (output / "provider-counts.json").write_text(json.dumps({"baseline": baseline, "candidate": candidate}, indent=2))
        by_case = {item["case"]: item for item in results}
        for item in results:
            assert baseline["calls"].get(item["requestId"]) == 1, item
            assert candidate["calls"].get(item["requestId"], 0) == 0, item
        for name in ("json", "body-selects-json"):
            assert by_case[name]["status"] == 200 and "application/json" in by_case[name]["contentType"], by_case[name]
        assert by_case["stream"]["status"] == 200 and by_case["stream"]["frames"][-1] == "[DONE]"
        assert by_case["http429"]["status"] == 429, by_case["http429"]
        assert by_case["http400"]["status"] == 400, by_case["http400"]
        assert by_case["http500"]["status"] == 502, by_case["http500"]
        for name in ("pre-token", "malformed", "empty"):
            assert by_case[name]["status"] == 502 and not by_case[name]["frames"], by_case[name]
        assert by_case["post-token"]["status"] == 200 and by_case["post-token"]["frames"] and "[DONE]" not in by_case["post-token"]["frames"]
        assert by_case["timeout"]["status"] == 504, by_case["timeout"]
        assert by_case["pre-token-timeout"]["status"] == 504 and not by_case["pre-token-timeout"]["frames"]
        for name in ("timeout", "pre-token-timeout", "total-deadline"):
            assert 1500 <= by_case[name]["elapsedMs"] < 4000, by_case[name]
        assert "[DONE]" not in by_case["total-deadline"]["frames"]
        assert baseline["cancelledRequests"].get(by_case["cancel"]["requestId"]) == 1
        (output / "PASS.json").write_text(json.dumps({"runId": run_id, "cases": len(cases), "status": "PASS"}, indent=2))
    finally:
        configure(args.baseline)
        (output / "transport-results.json").write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--gateway", default=endpoint("GATEWAY_A"))
    parser.add_argument("--baseline", default=endpoint("BASELINE"))
    parser.add_argument("--candidate", default=endpoint("CANDIDATE"))
    parser.add_argument("--output", required=True)
    run(parser.parse_args())
