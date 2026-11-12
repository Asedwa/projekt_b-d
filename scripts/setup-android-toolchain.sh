#!/usr/bin/env bash
# Provision a user-local Android toolchain for PlasmaWave (no sudo required).
#
# Installs into $PW_TOOLCHAIN (default: ~/Android):
#   - Eclipse Temurin JDK 17
#   - Android SDK command-line tools, platform-tools, platform, build-tools
#   - Android NDK
#   - Gradle distribution (used once to generate the Gradle wrapper)
# And for Rust:
#   - Android cross-compilation targets
#   - cargo-ndk
#
# Running this script implies acceptance of the Android SDK License Agreement.
set -euo pipefail

PW_TOOLCHAIN="${PW_TOOLCHAIN:-$HOME/Android}"
SDK="$PW_TOOLCHAIN/Sdk"
JDK_DIR="$PW_TOOLCHAIN/jdk-17"
GRADLE_VERSION="${GRADLE_VERSION:-8.11.1}"
CMDLINE_TOOLS_BUILD="${CMDLINE_TOOLS_BUILD:-11076708}"
NDK_VERSION="${NDK_VERSION:-27.2.12479018}"
PLATFORM="${PLATFORM:-android-35}"
BUILD_TOOLS="${BUILD_TOOLS:-35.0.0}"

mkdir -p "$PW_TOOLCHAIN" "$SDK"
cd "$PW_TOOLCHAIN"

log() { printf '\n==> %s\n' "$*"; }

if [ ! -x "$JDK_DIR/bin/java" ]; then
  log "Downloading Temurin JDK 17"
  curl -fL --retry 3 -o jdk17.tar.gz \
    "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse"
  rm -rf "$JDK_DIR" jdk-17-extract && mkdir jdk-17-extract
  tar -xzf jdk17.tar.gz -C jdk-17-extract
  mv jdk-17-extract/* "$JDK_DIR"
  rm -rf jdk-17-extract jdk17.tar.gz
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
java -version

if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  log "Downloading Android command-line tools ($CMDLINE_TOOLS_BUILD)"
  curl -fL --retry 3 -o cmdline-tools.zip \
    "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip"
  rm -rf cmdline-extract && mkdir cmdline-extract
  unzip -q cmdline-tools.zip -d cmdline-extract
  mkdir -p "$SDK/cmdline-tools"
  rm -rf "$SDK/cmdline-tools/latest"
  mv cmdline-extract/cmdline-tools "$SDK/cmdline-tools/latest"
  rm -rf cmdline-extract cmdline-tools.zip
fi

SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
log "Accepting Android SDK licenses"
yes | "$SDKMANAGER" --sdk_root="$SDK" --licenses >/dev/null || true

log "Installing SDK packages"
"$SDKMANAGER" --sdk_root="$SDK" --install \
  "platform-tools" \
  "platforms;$PLATFORM" \
  "build-tools;$BUILD_TOOLS" \
  "ndk;$NDK_VERSION"

if [ ! -x "$PW_TOOLCHAIN/gradle-$GRADLE_VERSION/bin/gradle" ]; then
  log "Downloading Gradle $GRADLE_VERSION"
  curl -fL --retry 3 -o gradle.zip \
    "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip"
  unzip -q gradle.zip -d "$PW_TOOLCHAIN"
  rm gradle.zip
fi

log "Installing Rust Android targets"
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android i686-linux-android

if ! command -v cargo-ndk >/dev/null 2>&1; then
  log "Installing cargo-ndk"
  cargo install cargo-ndk --locked
fi

cat > "$PW_TOOLCHAIN/env.sh" <<EOF
# Source this file: . "$PW_TOOLCHAIN/env.sh"
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_NDK_HOME="$SDK/ndk/$NDK_VERSION"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:$PW_TOOLCHAIN/gradle-$GRADLE_VERSION/bin:\$PATH"
EOF

log "Done. Run:  . \"$PW_TOOLCHAIN/env.sh\""
