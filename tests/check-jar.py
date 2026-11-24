"""Check packaged migration contents: python tests/check-jar.py JAR VERSION."""
import sys
import zipfile

jar, version = sys.argv[1:3]
expected = {
    "1.0.0": ["BOOT-INF/classes/db/migration/V1__base.sql"],
    "1.1.0": [
        "BOOT-INF/classes/db/migration/V1__base.sql",
        "BOOT-INF/classes/db/migration/V2__expand_source_type.sql",
    ],
}[version]
with zipfile.ZipFile(jar) as archive:
    migrations = sorted(
        name for name in archive.namelist()
        if name.startswith("BOOT-INF/classes/db/migration/") and name.endswith(".sql")
    )
assert migrations == expected, {"expected": expected, "actual": migrations}
print(f"Passed {version} packaged migration check.")
