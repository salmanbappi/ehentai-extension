"""Bump versionCode in the E-Hentai build file before building.

Keeps every published APK at a higher versionCode than the last one so
Mihon offers updates. Run from the repository root.
"""

import re
from pathlib import Path

BUILD_FILE = Path("src/all/ehentai/build.gradle.kts")

content = BUILD_FILE.read_text(encoding="utf-8")

match = re.search(r"^(\s*versionCode\s*=\s*)(\d+)(\s*)$", content, re.MULTILINE)
if match is None:
    raise SystemExit(f"error: versionCode not found in {BUILD_FILE}")

old_code = int(match.group(2))
new_code = old_code + 1

content = content[: match.start(2)] + str(new_code) + content[match.end(2) :]
BUILD_FILE.write_text(content, encoding="utf-8")

print(f"Bumped versionCode: {old_code} -> {new_code}")
