# PlasmaWave Architecture

PlasmaWave is a modular, open-source Android music player built with a **Rust-first** philosophy, Jetpack Compose UI, and inspired by the visual design language of **KDE Plasma Breeze Dark**.

---

## 1. High-Level System Architecture

```text
┌────────────────────────────────────────────────────────────────────────┐
│                        Android Layer (Kotlin)                          │
│                                                                        │
│   ┌──────────────────────────────────────────────────────────────┐     │
│   │                  Jetpack Compose UI (Breeze)                 │     │
│   │  • Home         • Search     • Queue     • Full Player       │     │
│   │  • Library      • Downloads  • Settings  • Long-Press Sheet  │     │
│   │  • Themes       • Mod Mgr    • Mini Player                   │     │
│   └──────────────────────────────┬───────────────────────────────┘     │
│                                  │ StateFlow / ViewModels              │
│   ┌──────────────────────────────┴───────────────────────────────┐     │
│   │            Android Media3 / MediaSessionService              │     │
│   │  • Audio playback session    • Lockscreen / Notification     │     │
│   │  • Headphone disconnect      • Audio focus                   │     │
│   └──────────────────────────────┬───────────────────────────────┘     │
│                                  │ UniFFI Bridge                       │
└──────────────────────────────────┼─────────────────────────────────────┘
                                   │
┌──────────────────────────────────┴─────────────────────────────────────┐
│                       Rust Core (plasmawave_core)                      │
│                                                                        │
│   ┌─────────────────────┐   ┌───────────────────┐   ┌──────────────┐   │
│   │    core-domain      │   │    core-queue     │   │ core-themes  │   │
│   │ Models, IDs, Rules  │   │ Shuffle, Repeat   │   │ JSON schema  │   │
│   └─────────▲───────────┘   └─────────▲─────────┘   └──────────────┘   │
│             │                         │                                │
│   ┌─────────┴───────────┐   ┌─────────┴─────────┐   ┌──────────────┐   │
│   │    core-library     │   │  core-downloads   │   │  core-mods   │   │
│   │ Favorites/Playlists │   │ Resume, Hash, Rate│   │ Wasmi Sandbx │   │
│   └─────────▲───────────┘   └─────────▲─────────┘   └──────────────┘   │
│             │                         │                                │
│   ┌─────────┴─────────────────────────┴─────────┐   ┌──────────────┐   │
│   │                core-storage                 │   │core-settings │   │
│   │     SQLite (rusqlite), Schema Migrations    │   │ Typed config │   │
│   └─────────────────────────────────────────────┘   └──────────────┘   │
│                                                                        │
│   ┌────────────────────────────────────────────────────────────────┐   │
│   │                    core-providers (Capability)                 │   │
│   │  • provider-local  : user audio via MediaStore (Full playback) │   │
│   │  • provider-deezer : public catalog API (previews)             │   │
│   │  • provider-dev    : test fixtures with clear visual badge     │   │
│   └────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Core Principles

1. **Rust owns all business logic:**
   - Queue management (ordering, shuffle permutation, repeat modes).
   - Local library indexing and SQLite database persistence.
   - Provider capability gating.
   - Authorized downloads (chunked streaming, resume with HTTP Range, SHA-256 validation).
   - Theme validation and registry.
   - WebAssembly mod sandboxing via `wasmi`.
   - Settings validation.

2. **Kotlin handles platform integrations:**
   - Rendering UI components using Jetpack Compose and design tokens.
   - System media playback via `androidx.media3.exoplayer`.
   - Android `MediaSessionService` for background playback, notifications, and lock screen controls.
   - Android Keystore secure storage for the Deezer web session.
   - Deezer web-session login and account-authorized Media3 streaming data source.
   - Android MediaStore / SAF file picking.

3. **Strict Capability Gating:**
   - Third-party media sources must declare capabilities explicitly.
   - The Rust Deezer provider uses the public catalog and reports public API capabilities only; Android's separate account session requests full playback using the web-player endpoints.
   - Deezer downloads in the existing public catalog path are preview clips, and the UI identifies them as such.
   - No mock data is presented as real account data in the Android sign-in flow.

---

## 3. Crate Responsibilities

| Crate | Responsibility |
|---|---|
| `core-domain` | Domain models (`Track`, `Playlist`, `Artist`), IDs, capabilities, errors. |
| `core-queue` | In-memory logical playback queue with Fisher–Yates shuffle and cycle repeat. |
| `core-providers` | Provider trait, capability matrices, and registry. |
| `provider-local` | Local media scanner interface (`content://` and `file://`). |
| `provider-deezer` | Deezer public API client (search, charts, preview clips) + OAuth state engine. |
| `provider-dev` | Clearly-labeled development fixtures for offline UI validation. |
| `core-downloads` | Authorized download manager with HTTP Range resume and SHA-256 verification. |
| `core-storage` | SQLite repository implementation with versioned migrations and WAL mode. |
| `core-themes` | JSON theme manifest schema validation and registry with built-in Breeze themes. |
| `core-mods` | WebAssembly mod runtime sandboxed via Wasmi with fuel budgeting and memory bounds. |
| `core-settings` | Persistent user configuration with fallback resilience. |
| `android-ffi` | UniFFI export layer generating typed Kotlin bindings. |

---

## 4. KDE Plasma Design System

PlasmaWave implements the KDE Plasma Breeze Dark aesthetic:
- **Base Background:** Deep graphite `#1B1E20` / `#202326`
- **Cards & Surfaces:** `#2A2E32` with `#3B4045` borders
- **Plasma Blue Accent:** `#3DAEE9`
- **Typography Tokens:** System clean sans-serif with proportional scaling
- **Shapes & Corners:** Subtle 6dp (small), 10dp (medium), 18dp (large) rounding
- **Components:** Compact list items, animated rounded bottom sheets, persistent mini-player.
