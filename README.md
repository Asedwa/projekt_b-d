# PlasmaWave 🎵🌊

**PlasmaWave** is a production-oriented, modular, open-source Android music player built with a **Rust-first** architecture and styled with **KDE Plasma's Breeze Dark** design language.

## Android APK

[**Download the latest Android APK**](https://github.com/Asedwa/plasmawave/releases/latest/download/PlasmaWave.apk)

The downloadable APK is a debug build of the latest published source. This private repository and its releases require GitHub access.

---

## 🌟 Key Features

- **Rust-First Core Engine:**
  - Fast, memory-safe playback queue, Fisher–Yates shuffling, cycle repeat modes.
  - SQLite local database persistence with versioned schema migrations (`rusqlite`) and WAL mode.
  - Strict capability-based provider architecture (`core-providers`).
  - Resilient download manager with HTTP Range chunking, resume support, and SHA-256 verification.
  - Sandboxed WebAssembly mod runtime via `wasmi` with fuel metering and memory bounds.
  - JSON-based custom theme engine with live switching and schema validation.
- **KDE Plasma Breeze Dark Visuals:**
  - Graphite background (`#1B1E20`), surface cards (`#2A2E32`), Plasma blue accent (`#3DAEE9`).
  - Jetpack Compose design tokens (colors, shapes, typography, motion).
  - Deezer-inspired long-press contextual sheet with ordered actions.
  - Persistent mini player and immersive full-screen audio player.
- **Android Media3 / ExoPlayer Integration:**
  - Background playback using `MediaSessionService`.
  - System lock screen controls, media notification, and Bluetooth headset disconnect handling.
  - Audio focus management.
- **Deezer Integration:**
  - Public catalog search and previews when signed out.
  - Sign in with Deezer's web login or an existing ARL session; session credentials are encrypted with Android Keystore and excluded from backups.
  - Signed-in playback requests full tracks permitted by the account. Deezer's internal web-player endpoints may change, and availability depends on the account, region, and track.
  - Favorites and playlists are loaded from the signed-in account; offline downloads from the public catalog are previews only.
- **Local Music Library:**
  - Fast device scanning via Android `MediaStore`.
- **Localization:**
  - English and Turkish translations included.

---

## Deezer sign-in

Open **Settings → Deezer ile Giriş Yap** (or **Library → Deezer**), and sign in on Deezer's page. Alternatively, enter your own ARL in Settings. An ARL grants access to your account, so never share it. Signing out removes the saved session. While signed out, Deezer search results only contain short previews; full playback requires an account with access to the selected track. The Android implementation uses Deezer's web-player protocol, inspired by [ReFreezer](https://github.com/DJDoubleD/refreezer), rather than the public catalog API. Web-based third-party sign-in may require the ARL option.

---

## 🏗️ Repository Layout

```text
plasmawave/
├── android/                   # Android Gradle project
│   ├── app/
│   │   ├── src/main/java/     # Jetpack Compose UI, ViewModels, Media3 Service
│   │   ├── src/main/jniLibs/  # Cross-compiled Rust cdylibs (arm64-v8a, x86_64)
│   │   └── src/main/res/      # Layouts, themes, English & Turkish strings
│   └── gradle/                # Gradle version catalog & wrapper
├── rust/                      # Cargo workspace
│   ├── Cargo.toml
│   └── crates/
│       ├── core-domain/       # Shared models, IDs, capabilities, errors
│       ├── core-library/      # Library domain services and persistence port
│       ├── core-queue/        # Logical queue, ordering, shuffle, repeat
│       ├── core-providers/    # Capability-gated provider trait & registry
│       ├── provider-deezer/   # Public Deezer catalog API client
│       ├── provider-local/    # Device audio file provider
│       ├── provider-dev/      # Development fixture provider
│       ├── core-downloads/    # Authorized download queue & resume manager
│       ├── core-storage/      # SQLite persistence and repositories
│       ├── core-themes/       # Theme manifest validation & registry
│       ├── core-mods/         # Wasmi WebAssembly sandboxed runtime
│       ├── core-settings/     # Persistent typed settings
│       └── android-ffi/       # UniFFI bridge and Kotlin binding generation
├── themes/                    # Built-in JSON themes (Breeze Dark, Light, Oxygen)
├── scripts/                   # Toolchain setup & build scripts
├── ARCHITECTURE.md            # System architecture documentation
└── ROADMAP.md                 # Development roadmap & status
```

---

## 🛠️ Building the Project

### Prerequisites
- Linux / macOS with Rust 1.80+ (`rustup`)
- Android SDK 34+ and Android NDK (r27+)
- JDK 17+

### 1. Build Rust Crates & Run Tests
```bash
cd plasmawave/rust
cargo test --workspace
cargo clippy --workspace -- -D warnings
```

### 2. Cross-Compile Rust for Android
```bash
cargo ndk -t arm64-v8a -t x86_64 -o ../android/app/src/main/jniLibs build -p android-ffi --release
```

### 3. Build Android APK
```bash
cd plasmawave/android
./gradlew assembleDebug
```
The output APK is located at:
`plasmawave/android/app/build/outputs/apk/debug/app-debug.apk`

---

## 📜 License
GPL-3.0-or-later.
