"""Measure a viewport: python tests/viewport.py USERS_JSON [BASE_URL] [REQUESTS] [CONCURRENCY]."""
import concurrent.futures
import json
import math
import sys
import time
import urllib.request

users = json.load(open(sys.argv[1], encoding="utf-8-sig"))
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080"
requests = int(sys.argv[3]) if len(sys.argv) > 3 else 250
concurrency = int(sys.argv[4]) if len(sys.argv) > 4 else 1
operator = next(user for user in users if user["role"] == "OPERATOR")


def fetch(path, token=None, data=None):
    body = json.dumps(data).encode() if data is not None else None
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(base + path, body, headers), timeout=15) as response:
        return json.load(response)


session = fetch("/api/v1/auth/login", data={
    "username": operator["username"], "password": operator["password"]})
token = session["accessToken"]
world = fetch("/api/v1/assets?bbox=-180,-90,180,90&limit=5000", token)
assert not world["truncated"] and len(world["items"]) >= 1000, len(world["items"])

longitudes = sorted(item["lon"] for item in world["items"])
# Pick a strict gap near 1,000 rows so the bbox has a deterministic count without
# depending on how equal longitudes are ordered.
match_count = next(
    index for index in range(1000, 899, -1)
    if longitudes[index - 1] < longitudes[index]
)
maximum = (longitudes[match_count - 1] + longitudes[match_count]) / 2
bbox = f"-180,-90,{maximum},90"
probe = fetch("/api/v1/assets?bbox=" + bbox + "&limit=5000", token)
assert len(probe["items"]) == match_count and not probe["truncated"], len(probe["items"])


def sample(_):
    started = time.perf_counter()
    result = fetch("/api/v1/assets?bbox=" + bbox + "&limit=1000", token)
    elapsed_ms = (time.perf_counter() - started) * 1000
    assert len(result["items"]) == match_count and not result["truncated"]
    return elapsed_ms


with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
    timings = sorted(pool.map(sample, range(requests)))

p95 = timings[math.ceil(len(timings) * 0.95) - 1]
print(json.dumps({
    "requests": requests,
    "concurrency": concurrency,
    "assetsPerResponse": match_count,
    "p95Ms": round(p95, 2),
    "maximumMs": round(timings[-1], 2),
    "bbox": bbox,
}))
if p95 >= 100:
    sys.exit(1)
