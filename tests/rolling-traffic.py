"""Generate authenticated read/write traffic during an upgrade."""
import argparse
import json
import threading
import time
import urllib.error
import urllib.request
import uuid


def call(base, path, token=None, data=None, content_type="application/json"):
    body = data.encode() if isinstance(data, str) else json.dumps(data).encode() if data is not None else None
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        with urllib.request.urlopen(
                urllib.request.Request(base + path, body, headers), timeout=10) as response:
            response.read()
            return response.status
    except (urllib.error.URLError, TimeoutError):
        return 0


parser = argparse.ArgumentParser()
parser.add_argument("users")
parser.add_argument("--base-url", default="http://127.0.0.1:8080")
parser.add_argument("--seconds", type=int, default=120)
args = parser.parse_args()
users = json.load(open(args.users, encoding="utf-8-sig"))


def login(role):
    user = next(item for item in users if item["role"] == role)
    body = json.dumps({"username": user["username"], "password": user["password"]}).encode()
    with urllib.request.urlopen(urllib.request.Request(
            args.base_url + "/api/v1/auth/login", body, {"Content-Type": "application/json"})) as response:
        return json.load(response)["accessToken"]


feed = login("INGEST")
operator = login("OPERATOR")
prefix = uuid.uuid4().hex
lock = threading.Lock()
totals = {"requests": 0, "failures": 0, "ingest": 0, "reads": 0}
deadline = time.time() + args.seconds


def record(status, kind):
    with lock:
        totals["requests"] += 1
        totals[kind] += 1
        if status != 200:
            totals["failures"] += 1


def writer():
    index = 0
    while time.time() < deadline:
        report = json.dumps({
            "reportId": prefix + str(index),
            "assetId": "ROLLING-TEST",
            "observedAt": f"2026-11-24T12:{index // 60 % 60:02}:{index % 60:02}.000Z",
            "lat": 0,
            "lon": 0,
            "source": "MOB-UPGRADE",
        })
        record(call(args.base_url, "/api/v1/reports/batch", feed, report, "application/x-ndjson"), "ingest")
        index += 1
        time.sleep(0.1)


def reader():
    paths = ["/api/v1/stats", "/api/v1/assets?bbox=-180,-90,180,90&limit=5000"]
    index = 0
    while time.time() < deadline:
        record(call(args.base_url, paths[index % len(paths)], operator), "reads")
        index += 1
        time.sleep(0.05)


threads = [threading.Thread(target=writer), threading.Thread(target=reader)]
for thread in threads:
    thread.start()
for thread in threads:
    thread.join()
print(json.dumps(totals))
if totals["failures"]:
    raise SystemExit(1)
