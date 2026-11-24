"""Run after tests/contract.py against v1.1.0: python tests/source-type.py USERS_JSON [BASE_URL]."""
import json
import sys

import contract as scenario


def track(source_type=None):
    query = "" if source_type is None else "?sourceType=" + source_type
    return scenario.call("/api/v1/assets/" + scenario.asset + "/track" + query, scenario.operator)


status, body = track()
assert status == 200 and len(body["items"]) == 9
assert all(point["sourceType"] == "SATELLITE" for point in body["items"])
assert len(track("SATELLITE")[1]["items"]) == 9
assert track("MOBILE")[1]["items"] == []
assert track("INVALID")[0] == 400

for name, source, expected in (
    ("ground", "GND-1", "GROUND"),
    ("mobile", "MOB-1", "MOBILE"),
    ("unknown", "ship-radio", "UNKNOWN"),
    ("missing", None, "UNKNOWN"),
):
    report = json.loads(scenario.point(name, 55, 0, 0))
    if source is None:
        report.pop("source", None)
    else:
        report["source"] = source
    assert scenario.batch(json.dumps(report))["accepted"] == 1
    matches = track(expected)[1]["items"]
    assert any(point["reportId"] == report["reportId"] and point["sourceType"] == expected for point in matches)

print("Passed sourceType classification, response, filter, missing-source, and invalid-filter checks.")
