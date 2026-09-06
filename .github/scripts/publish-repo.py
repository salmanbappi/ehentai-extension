"""Publish freshly built extensions into the legacy-format extension repo.

Reads the keiyoushi-source-info.json + release APKs produced by the modern
Gradle build (downloaded as CI artifacts under ~/apk-artifacts) and merges
them into the checked-out extension repository, using the same legacy
index schema as the other extensions published there:

    index.json / index.min.json  -> JSON array of extension entries
    apk/                         -> extension APKs
    icon/                        -> per-package icons
    repo.json / index.html       -> repo metadata & listing page

Usage (from inside the checked-out extension repo checkout):

    python <source-repo>/.github/scripts/publish-repo.py <source-repo>
"""

import hashlib
import html
import json
import shutil
import sys
from pathlib import Path

ARTIFACTS_DIR = Path.home() / "apk-artifacts"
REPO_DIR = Path.cwd()
SOURCE_DIR = Path(sys.argv[1]).resolve()

ICON_FILE = "res/mipmap-xhdpi/ic_launcher.png"
UNIVERSAL_SIG = "212199045691887b32eb2397f167f4b7d53a73131119975df9914595bc95880a"

REPO_APK_DIR = REPO_DIR / "apk"
REPO_ICON_DIR = REPO_DIR / "icon"
REPO_APK_DIR.mkdir(parents=True, exist_ok=True)
REPO_ICON_DIR.mkdir(parents=True, exist_ok=True)

new_entries: list[dict] = []
built_pkgs: list[str] = []
built_modules: list[str] = []

for info_file in sorted(ARTIFACTS_DIR.glob("**/keiyoushi-source-info.json")):
    info = json.loads(info_file.read_text(encoding="utf-8"))

    pkg = info["packageName"]
    module = info["module"]
    built_pkgs.append(pkg)
    built_modules.append(module)

    apk = next((info_file.parent / "outputs/apk/release").glob("*.apk"), None)
    if apk is None:
        raise FileNotFoundError(f"{pkg}: no release apk found under {info_file.parent}")

    sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()

    # Replace any previously published APKs of this module, then store the new one.
    for stale in REPO_APK_DIR.glob(f"tachiyomi-{module}-v*.apk"):
        print(f"removing stale apk {stale.name}")
        stale.unlink(missing_ok=True)
    shutil.copyfile(apk, REPO_APK_DIR / apk.name)

    # Copy the extension icon from the source repo.
    icon_src = SOURCE_DIR.joinpath("src", *module.split("."), *ICON_FILE.split("/"))
    if icon_src.exists():
        shutil.copyfile(icon_src, REPO_ICON_DIR / f"{pkg}.png")
    else:
        print(f"warning: no icon found at {icon_src}")

    entry = {
        "name": f"Tachiyomi: {info['name']}",
        "pkg": pkg,
        "apk": apk.name,
        "lang": module.split(".")[0],
        "code": info["versionCode"],
        "version": info["versionName"],
        "nsfw": 1 if info["contentWarning"] == 3 else 0,
        "hasReadme": 0,
        "hasChangelog": 0,
        "icon": f"icon/{pkg}.png",
        "sig": UNIVERSAL_SIG,
        "sources": [
            {
                "name": source["name"],
                "id": str(source["id"]),
                "lang": source["lang"],
                "baseUrl": source["baseUrl"],
            }
            for source in info["sources"]
        ],
        "sha256": sha256,
    }
    new_entries.append(entry)
    print(f"prepared {pkg} v{info['versionName']} ({len(info['sources'])} sources)")

if not new_entries:
    raise SystemExit("error: no keiyoushi-source-info.json found in artifacts")

# Merge with the already-published index: rebuilds replace, deletions prune.
index_path = REPO_DIR / "index.json"
if index_path.exists():
    index = json.loads(index_path.read_text(encoding="utf-8"))
else:
    index = []

index = [
    item
    for item in index
    if item.get("pkg") not in built_pkgs and "example" not in item.get("pkg", "")
]
index.extend(new_entries)
index.sort(key=lambda item: item["pkg"])

index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2), encoding="utf-8")
(REPO_DIR / "index.min.json").write_text(
    json.dumps(index, ensure_ascii=False, separators=(",", ":")), encoding="utf-8"
)

repo_info = {
    "meta": {
        "name": "SalmanBappi Manga Repo",
        "shortName": "SBManga",
        "website": "https://salmanbappi.github.io/salmanbappi-manga-extension/",
        "signingKeyFingerprint": UNIVERSAL_SIG,
    }
}
(REPO_DIR / "repo.json").write_text(json.dumps(repo_info, indent=2), encoding="utf-8")

with (REPO_DIR / "index.html").open("w", encoding="utf-8") as index_html_file:
    index_html_file.write(
        '<!DOCTYPE html>\n<html>\n<head>\n<meta charset="UTF-8">\n'
        "<title>apks</title>\n</head>\n<body>\n<pre>\n"
    )
    for entry in index:
        apk_escaped = "apk/" + html.escape(entry["apk"].split("/")[-1])
        name_escaped = html.escape(entry["name"])
        index_html_file.write(f'<a href="{apk_escaped}">{name_escaped}</a>\n')
    index_html_file.write("</pre>\n</body>\n</html>\n")

print(f"published {len(new_entries)} extension(s), index now has {len(index)} entries")
