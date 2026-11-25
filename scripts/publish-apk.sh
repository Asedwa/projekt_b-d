#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_dir"

if [[ -n "$(git status --porcelain)" ]]; then
    echo "Commit the requested source changes before publishing an APK." >&2
    exit 1
fi
if [[ "$(git branch --show-current)" != "main" ]]; then
    echo "Publish from the main branch." >&2
    exit 1
fi

git fetch --quiet origin main
target="$(git rev-parse HEAD)"
if [[ "$(git rev-parse origin/main)" != "$target" ]]; then
    echo "Push the source commit to origin/main before publishing its APK." >&2
    exit 1
fi
repo_name="$(gh repo view --json nameWithOwner --jq .nameWithOwner)"

# The Rust libraries are ignored by Git, so rebuild them from this commit.
(
    cd rust
    cargo ndk -t arm64-v8a -t x86_64 -o "$repo_dir/android/app/src/main/jniLibs" build -p android-ffi --release --locked
)
(
    cd android
    ./gradlew :app:assembleRelease
)
if [[ -n "$(git status --porcelain)" || "$(git rev-parse HEAD)" != "$target" ]]; then
    echo "Source changed during the build; APK publication stopped." >&2
    exit 1
fi

version="$(sed -nE 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' android/app/build.gradle.kts | head -n 1)"
if [[ ! "$version" =~ ^[[:alnum:]][[:alnum:]._-]*$ ]]; then
    echo "Could not read a safe Android version name." >&2
    exit 1
fi
artifact_dir="$(mktemp -d "${TMPDIR:-/tmp}/projekt-bd-apk.XXXXXX")"
trap 'rm -rf -- "$artifact_dir"' EXIT
artifact_name="projekt_b-d-${version}.apk"
cp android/app/build/outputs/apk/release/app-release.apk "$artifact_dir/$artifact_name"

tag="v${version}"
if gh release view "$tag" --repo "$repo_name" >/dev/null 2>&1; then
    tag="v${version}-$(date -u +%Y%m%d%H%M%S)"
fi
release_notes="${RELEASE_NOTES:-projekt_b-d v${version} Android release APK (kaynak commit: ${target}).}"
# Publish only after the upload succeeds, so the latest link never points at an empty release.
gh release create "$tag" "$artifact_dir/$artifact_name" --repo "$repo_name" --target "$target" \
    --title "projekt_b-d $version" --notes "$release_notes" --draft
gh release edit "$tag" --repo "$repo_name" --draft=false --latest
echo "Published $repo_name release $tag for $target."
