# PlasmaWave Roadmap

**Project codename:** PlasmaWave  
**Architecture:** Rust-first modular core, Kotlin Jetpack Compose UI, UniFFI bridge, Android Media3 playback.

---

## Phases & Status

### Phase 0: Research & Architecture ✅
- [x] Toolchain verification (Temurin JDK 17, Android SDK 35, NDK 27.2, Rust Android targets, cargo-ndk).
- [x] Official Deezer developer capability assessment (no new app registrations, restricted OAuth without secret-bearing backend, public catalog endpoints operational).
- [x] Architecture design document (`ARCHITECTURE.md`).
- [x] Roadmap and test matrix (`ROADMAP.md`).

### Phase 1: Working Rust Core Foundation ✅
- [x] Root Cargo workspace with 13 modular crates.
- [x] `core-domain`: Domain entities (`Track`, `Playlist`, `Artist`), IDs, capabilities, errors.
- [x] `core-queue`: Logical queue, Fisher–Yates shuffle, repeat modes, state snapshots.
- [x] `core-providers`: Provider trait, capability matrices, and registry.
- [x] `provider-dev`: Clearly badged development fixtures for offline/fixture testing.
- [x] `provider-local`: Local audio indexing and metadata extraction interface.
- [x] `provider-deezer`: Public catalog search, chart, preview clips, and OAuth state validator.
- [x] `core-library`: Playlist and favorites management services.
- [x] `core-settings`: Typed configuration with safe fallback resilience.
- [x] `core-downloads`: Authorized download manager with HTTP Range resume and SHA-256 checks.
- [x] `core-themes`: JSON theme validation and registry with built-in Breeze themes.
- [x] `core-mods`: Sandboxed WebAssembly runtime using Wasmi with fuel budgeting and memory bounds.
- [x] `core-storage`: SQLite persistence layer with schema migrations and WAL mode.
- [x] `android-ffi`: UniFFI bridge and exported `PlasmaWaveCore` facade.

### Phase 2: Android Project & Jetpack Compose UI (In Progress ⏳)
- [x] Android project structure (Gradle 8.11, AGP 8.7, Kotlin 2.0).
- [ ] Jetpack Compose Breeze Dark design system tokens (colors, typography, shapes, spacing).
- [ ] Navigation architecture (Bottom navigation bar, route handling).
- [ ] Screens:
  - [ ] Home Screen (recents, quick play, chart previews).
  - [ ] Library Screen (local tracks, playlists, favorites tabs).
  - [ ] Playlist Detail Screen.
  - [ ] Search Screen (instant unified search across local, fixtures, and public catalog).
  - [ ] Persistent Mini Player.
  - [ ] Full Screen Player (album art, scrub bar, shuffle, repeat, track info).
  - [ ] Queue Screen (reordering, drag & drop, remove).
  - [ ] Downloads Screen (progress bars, pause, resume, cancel).
  - [ ] Settings Screen (theme selection, language, Wi-Fi downloads, dev fixtures toggle).
  - [ ] Themes Screen (built-in themes preview, custom JSON theme import).
  - [ ] Mod Manager Screen (installed mods, permissions, enable/disable toggle).
  - [ ] Song Long-Press Contextual Menu (animated rounded Breeze Dark bottom sheet).

### Phase 3: Android Media3 / ExoPlayer Integration
- [ ] MediaSessionService for background playback and notification.
- [ ] System audio focus & Bluetooth headphone disconnect handling.
- [ ] Synchronization between Rust `core-queue` and Android `ExoPlayer`.

### Phase 4: Localization & Hardening
- [ ] English strings (`res/values/strings.xml`).
- [ ] Turkish strings (`res/values-tr/strings.xml`).
- [ ] Keystore-backed secure storage bridge.
- [ ] Signed-ready release build configuration.
