# Project Operating Instructions (`AGENTS.md`)
> **Notice:** This project inherits user persona, confirmation gates, and documentation blueprints from the global master `AGENTS.md`. Do not duplicate those rules here.

## 1. Project Tech Stack & Environment
* **Runtime / Language:** Kotlin 2.1+, Java 17, Android SDK 36 (minSdk 26, compileSdk 37)
* **Framework / Host:** Jetpack Compose + Material 3, native `libmpv` (C/C++ JNI)
* **Target OS / Build Output:** Android APK / AAB (tag-triggered cloud builds via GitHub Actions on Ubuntu)

## 2. Mandatory Verification & Build Commands
* **Local Unit Tests:** `./gradlew test` (or `./gradlew testStandardReleaseUnitTest`)
* **Git Version Tagging:** `git tag -a vX.Y.Z -m "Release vX.Y.Z"`
* **Release Pipeline:** Push version tag to `origin` to trigger `.github/workflows/release.yml`
* **Release Commit Subject:** MUST match `chore(release): vX.Y.Z`

## 3. Repository Directory Structure & Conventions
* `app/src/main/java/app/gyrolet/mpvrx/` - Core application source.
  * `ui/player/` - Video player activity, surface, controls, gestures, and playback models.
  * `ui/bookmarks/` - Cross-video bookmark gallery screen and UI components.
  * `domain/` - Business logic, bookmark matching, tag rules, and secure media registry.
  * `database/` - Room SQLite database, DAOs, entities, and repositories.
  * `repository/` - Data repositories (Watch stats, etc.).
* `docs/` - Core documentation suite (`PRODUCT.md`, `ARCHITECTURE.md`, `DECISIONS_AND_TRAPS.md`).
* `.github/workflows/` - Automated workflows (`build.yml`, `release.yml`, `preview.yml`, `mapping.yml`).

## 4. Repository-Specific Engineering Traps
* **TRAP 1: Path Normalization:** Never use `java.net.URI` for local file path normalization; opaque URIs and non-ASCII characters yield null path, causing `null.trim()` `NullPointerException` crashes in Room flows. Use pure string normalization in `PlaybackIdentity.normalizeLocalPath`.
* **TRAP 2: Release Workflow Subject:** Never use arbitrary commit messages when pushing releases. `.github/workflows/release.yml` strictly enforces `chore(release): vX.Y.Z` and rejects the build if `versionName` does not match `CHANGELOG.md`.
* **TRAP 3: Non-Interactive Git:** All Git commands must execute non-interactively with `GIT_TERMINAL_PROMPT=0`.
