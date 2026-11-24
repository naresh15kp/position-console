"""Fixed-contract negative-path checks: python tests/api-contract.py USERS_JSON [BASE_URL]."""
import json
import sys
import urllib.error
import urllib.request

users = json.load(open(sys.argv[1], encoding="utf-8-sig"))
base = sys.argv[2] if len(sys.argv) > 2 else "http://localhost:8080"


def request(path, token=None, data=None, content_type=None, headers=None):
    body = data.encode() if isinstance(data, str) else json.dumps(data).encode() if data is not None else None
    request_headers = dict(headers or {})
    if token:
        request_headers["Authorization"] = "Bearer " + token
    if content_type:
        request_headers["Content-Type"] = content_type
    try:
        response = urllib.request.urlopen(
            urllib.request.Request(base + path, body, request_headers), timeout=15)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read()
        return response.status, response.headers, json.loads(raw) if raw else None


def login(role):
    user = next(item for item in users if item["role"] == role)
    status, _, body = request("/api/v1/auth/login", data={
        "username": user["username"], "password": user["password"]
    }, content_type="application/json")
    assert status == 200
    assert set(body) == {"accessToken", "tokenType", "expiresInSec", "user"}
    assert body["tokenType"] == "Bearer" and body["user"] == {
        "username": user["username"], "role": role}
    return body["accessToken"]


feed = login("INGEST")
operator = login("OPERATOR")
supervisor = login("SUPERVISOR")


def problem(path, expected_status, expected_code, **kwargs):
    status, headers, body = request(path, **kwargs)
    assert status == expected_status, body
    assert headers.get_content_type() == "application/problem+json", headers
    assert body["status"] == expected_status and body["code"] == expected_code, body
    assert {"type", "title", "status", "code"} <= set(body), body


problem("/api/v1/auth/login", 401, "UNAUTHENTICATED",
        data={"username": "missing", "password": "wrong"}, content_type="application/json")
problem("/api/v1/assets?bbox=-180,-90,180,90", 401, "UNAUTHENTICATED")
problem("/api/v1/assets?bbox=-180,-90,180,90", 403, "FORBIDDEN", token=feed)
problem("/api/v1/reports/batch", 403, "FORBIDDEN", token=operator,
        data="{}", content_type="application/x-ndjson")
problem("/api/v1/reports/batch", 415, "UNSUPPORTED_MEDIA_TYPE", token=feed,
        data="{}", content_type="application/json")
problem("/api/v1/assets?bbox=broken", 400, "VALIDATION_FAILED", token=operator)
problem("/api/v1/assets?bbox=-180,-90,180,90&limit=0", 400, "VALIDATION_FAILED", token=operator)
problem("/api/v1/assets/DOES-NOT-EXIST", 404, "NOT_FOUND", token=supervisor)

request_id = "contract-request-123"
status, headers, body = request("/api/v1/stats", token=operator, headers={"X-Request-Id": request_id})
assert status == 200 and headers["X-Request-Id"] == request_id, (headers, body)
assert set(body) == {"received", "accepted", "duplicates", "rejected", "assets", "alerts"}
assert set(body["alerts"]) == {"open", "claimed", "resolved"}

print("Passed fixed authentication, role, media type, problem, validation, and request-ID checks.")
