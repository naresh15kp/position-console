"""Run against a fresh deployment: python tests/contract.py USERS_JSON [BASE_URL]."""
import concurrent.futures
import json
import sys
import urllib.error
import urllib.request
import uuid

users = json.load(open(sys.argv[1], encoding="utf-8-sig"))
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080"
prefix = uuid.uuid4().hex[:12].upper()
asset = "TEST-" + prefix

def call(path, token=None, data=None, content_type="application/json"):
    body = data.encode() if isinstance(data, str) else json.dumps(data).encode() if data is not None else None
    headers = {"Content-Type": content_type}
    if token:
        headers["Authorization"] = "Bearer " + token
    try:
        response = urllib.request.urlopen(urllib.request.Request(base + path, body, headers), timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, json.load(response)

def login(user):
    status, body = call("/api/v1/auth/login", data={k: user[k] for k in ("username", "password")})
    assert status == 200, body
    return body["accessToken"]

feed = login(next(user for user in users if user["role"] == "INGEST"))
operators = [login(user) for user in users if user["role"] == "OPERATOR"][:2]
assert len(operators) == 2
operator = operators[0]

def point(name, second, lat, lon):
    return json.dumps({"assetId": asset, "reportId": prefix + name,
                       "observedAt": f"2026-11-02T06:00:{second:02}.120Z",
                       "lat": lat, "lon": lon, "source": "SAT-2"})

def batch(*lines):
    status, result = call("/api/v1/reports/batch", feed, "\n".join(lines), "application/x-ndjson")
    assert status == 200, result
    return result

def alerts():
    status, result = call("/api/v1/alerts?assetId=" + asset, operator)
    assert status == 200, result
    return result["items"]

with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    outside = point("out", 20, 0, 0)
    results = list(pool.map(batch, [outside] * 4))
    assert sum(r["accepted"] for r in results) == 1
    assert sum(r["duplicates"] for r in results) == 3

inside = point("in", 25, 38.1, 168.1)
late = point("late", 10, 38.1, 168.1)
mixed = batch("[]", late, '{"reportId":"missing"}')
assert mixed["accepted"] == 1 and mixed["rejections"] == [
    {"line": 1, "code": "MALFORMED_JSON"}, {"line": 3, "code": "MISSING_FIELD"}], mixed
assert alerts() == []
assert call("/api/v1/assets/" + asset, operator)[1]["reportId"] == prefix + "out"
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
    results = list(pool.map(batch, [inside] * 4))
    assert sum(r["accepted"] for r in results) == 1
assert batch(point("out2", 30, 0, 0), point("in2", 35, 38.1, 168.1))["accepted"] == 2
zone = [a for a in alerts() if a["zoneId"] == "Z-01"]
assert len(zone) == 1, zone
path = "/api/v1/alerts/" + zone[0]["alertId"]
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    claims = list(pool.map(lambda token: call(path + "/claim", token, {}), operators))
assert sorted(s for s, _ in claims) == [200, 409], claims
winner = operators[0 if claims[0][0] == 200 else 1]
assert call(path + "/claim", winner, {})[0] == 200
assert call(path + "/resolve", winner, {"note": "Reviewed"})[0] == 200
assert call(path + "/resolve", winner, {"note": "Reviewed twice"})[0] == 200
assert [e["action"] for e in call(path + "/audit", winner)[1]["items"]] == ["OPENED", "CLAIMED", "RESOLVED"]
assert batch(point("still", 40, 38.1, 168.1))["accepted"] == 1
assert len([a for a in alerts() if a["zoneId"] == "Z-01"]) == 1
assert batch(point("out3", 45, 0, 0), point("in3", 50, 38.1, 168.1))["accepted"] == 2
assert len([a for a in alerts() if a["zoneId"] == "Z-01"]) == 2
track = call("/api/v1/assets/" + asset + "/track", winner)[1]["items"]
assert len(track) == 8 and track[0]["reportId"] == prefix + "late", track
print("Passed parallel duplicate/claim, late report, active alert, audit and rejection checks.")