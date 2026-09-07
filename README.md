# E-Hentai Extension (Mihon/Tachiyomi)

Standalone [Mihon](https://mihon.app) / Tachiyomi extension for **e-hentai.org / exhentai.org**, built on the modern [keiyoushi](https://github.com/keiyoushi) Gradle build system. Extracted from [yuzono/cursed-manga-extensions](https://github.com/yuzono/cursed-manga-extensions) (Apache-2.0) with only the e-hentai extension kept — everything needed to build is included in this repo.

## What's inside

| Path | Purpose |
|:--|:--|
| `src/all/ehentai/` | The extension source (7 Kotlin files, icons, `build.gradle.kts`) |
| `core/` | keiyoushi utils library (`getPreferencesLazy`, …) the extension compiles against |
| `compiler/` | KSP processor that turns the `source { lang = … }` blocks in `build.gradle.kts` into factory classes |
| `gradle/build-logic/` | Gradle plugins that build/sign the extension APK |
| `.github/` | CI: build on push, publish into your extension repo |
| `common/` | Shared manifest template + proguard rules |

The extension registers **17 sources** (one per language: ja, en, zh, nl, fr, de, hu, it, ko, pl, pt-BR, ru, es, th, vi, none, other) under the name "E-Hentai", exactly like upstream.

## Extension features

- Popular / Latest / Search with full filter support (genres, tags, min rating, page ranges, favorites/watched lists)
- Tag namespaces parsed into Mihon's tag list; galleries become single-chapter entries
- **ExHentai support (automatic)** — open the source in WebView, log in on e-hentai.org, then just browse. The extension picks up `ipb_member_id` / `ipb_pass_hash` from the WebView by itself, then completes the ExHentai sign-in automatically by walking the same SSO bounce a browser uses (exhentai.org → `forums.e-hentai.org/remoteapi.php` → `?poni=`) and capturing the `igneous` cookie. No manual cookie entry needed; the settings fields remain as an override.
- "Force e-hentai" toggle (off by default) to stay on e-hentai.org, "Original Image" toggle for full-resolution images
- Deep links for `e-hentai.org/g/…` and `exhentai.org/g/…` URLs
- `id:<gallery id>` search prefix and pasting a gallery URL directly into search

## Setup (one-time)

1. Create a new **empty** GitHub repository under your account (any name, e.g. `ehentai-extension`). Do **not** initialize it with a README.
2. Push this folder to it:
   ```bash
   cd ehentai-project
   git remote add origin https://github.com/<you>/<repo>.git
   git push -u origin main
   ```
3. In the new repo, **Settings → Secrets and variables → Actions** → add these repository secrets (same values as `my-manga-sources`):

   | Secret | Value |
   |:--|:--|
   | `SIGNING_KEY` | base64 of your `signingkey.jks` |
   | `ALIAS` | keystore alias |
   | `KEY_STORE_PASSWORD` | keystore password |
   | `KEY_PASSWORD` | key password |
   | `BOT_PAT` | personal access token with `repo` scope (used to push to the extension repo) |

4. Push any change (or run the workflow manually from the **Actions** tab) — CI will:
   - bump `versionCode` in `src/all/ehentai/build.gradle.kts` and commit the bump,
   - build + sign `tachiyomi-all.ehentai-v1.4.<code>.apk`,
   - publish the APK, icon and index entry into **`salmanbappi/salmanbappi-manga-extension`** (`main` branch) using the same legacy index format as your other extensions.

Your existing repo URL keeps working — E-Hentai simply appears next to your other extensions:

```
https://salmanbappi.github.io/salmanbappi-manga-extension/index.min.json
```

> The publish job only runs when this repo lives under the `salmanbappi/` account (see the `startsWith(github.repository, 'salmanbappi/')` guard in the workflow). Adjust the repository name, account and repo URLs in `.github/workflows/build_push.yml` and `.github/scripts/publish-repo.py` if yours differ.

## Updating the extension later

Copy the latest `src/all/ehentai/` from [yuzono/cursed-manga-extensions](https://github.com/yuzono/cursed-manga-extensions/tree/master/src/all/ehentai) over this one and push. If upstream changed `versionCode`, reset it lower than your current published code (or just let the auto-bump handle it — CI always increments before building).

## Building locally (optional)

Requires JDK 17 and an Android SDK (API 37 build tools):

```bash
./gradlew :src:all:ehentai:assembleRelease
# → src/all/ehentai/build/outputs/apk/release/tachiyomi-all.ehentai-v1.4.<code>.apk
```

The APK is signed with `signingkey.jks` in the repo root (git-ignored), reading `KEY_STORE_PASSWORD`, `ALIAS` and `KEY_PASSWORD` from the environment. Without a keystore the build still succeeds — it just uses the debug key.

## Notes

- The extension is flagged **NSFW**; enable *Settings → Advanced → Show NSFW extensions* in Mihon to see it.
- If you already have keiyoushi's E-Hentai installed, uninstall it first — source IDs are identical, and Mihon will refuse to "update" it with an APK signed by a different key.
- `pt-BR` keeps its historical source ID `7151438547982231541` so existing pt-BR libraries carry over from the upstream extension; all other languages get the standard generated IDs.
- Both this repo's CI and the `my-manga-sources` CI write to the same published repo — avoid running both at the same moment (the last push wins for the index files).
