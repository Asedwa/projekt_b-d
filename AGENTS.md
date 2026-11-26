Projekt Better Deezer — AGENTS.md
1. Primary objective
This project follows a Rust-first development policy. The long-term goal is to implement all technically feasible application logic in Rust, with a shared core for Android and desktop. Platform-specific code must remain minimal. These rules apply to all new features, fixes, refactors and optimizations.
- Rust is the default and mandatory language for new application logic.
- Do not introduce new Kotlin or Java business logic.
- Kotlin and Java are permitted only for necessary Android framework integration, existing native UI, lifecycle, permissions, MediaSession, notifications and other platform bridges where a Rust solution is not practical or reliable.
- Before adding non-Rust logic, check whether it can be implemented in the existing Rust core. Explain necessary exceptions.
- Do not rewrite the entire application or replace the UI framework without explicit user approval.
- Prioritize correctness, stability and maintainability over maximizing Rust line counts.
2. Architecture and shared core
- Inspect the existing repository structure and Cargo workspace before creating modules or crates.
- Reuse existing Rust crates and the existing UniFFI integration where applicable. Do not create parallel implementations of existing functionality.
- Keep platform-independent business logic, data models, serialization, persistence, networking, caching, search, filtering, sorting, theme computation, playlist and queue logic in Rust whenever feasible.
- Audio playback logic and state management should be Rust-first. Preserve working platform audio integrations until an alternative has been validated.
- Share platform-independent code between Android and desktop. Keep platform-specific dependencies behind adapters or appropriate target-specific configuration.
- Preserve clean dependency direction: platform UI/bridge -> Rust application services -> domain/infrastructure interfaces. Avoid cyclic dependencies.
- Maintain a single authoritative source of truth for application state. Do not duplicate state machines across Rust and Kotlin.
3. Kotlin-to-Rust migration
- Migrate incrementally, one bounded subsystem at a time. Do not perform unrelated migrations as part of a feature or fix.
- Inspect existing behavior, callers, data contracts, persistence formats and platform requirements before moving code.
- Preserve existing functionality, public interfaces, settings, credentials, downloads and user data.
- Implement the replacement in Rust, connect it through the existing bridge, validate compilation and relevant behavior, then remove obsolete implementations when safe.
- If behavior cannot be validated, disclose the limitation; do not claim migration is fully verified.
- Request approval before destructive data migrations, major architecture changes or replacing a working platform subsystem.
- Avoid adding permanent fallback implementations that duplicate business logic.
4. Rust engineering standards
- Follow the repository's existing Rust edition, toolchain, formatting and module conventions.
- Use idiomatic Rust, explicit types at public boundaries, clear ownership and Result-based error handling.
- Avoid unwrap(), expect(), panic!() and unchecked indexing on fallible production paths. Never silently discard important errors.
- Prefer safe Rust. Use unsafe only when unavoidable, document its safety invariants and keep its scope minimal.
- Avoid unnecessary cloning, allocations, serialization, locks and reference-counted ownership.
- Use the existing async runtime and synchronization strategy. Do not introduce competing runtimes without approval.
- Do not block UI threads or async executor threads with expensive operations or blocking I/O.
- Keep concurrency bounded; support cancellation, lifecycle cleanup and predictable resource release.
- Use meaningful structured logging; never log credentials, tokens, cookies or sensitive user data.
- Add dependencies only when justified. Reuse existing dependencies, avoid unnecessary features and do not perform unrelated dependency upgrades.
5. FFI and Android integration
- Use the existing UniFFI binding generation workflow where applicable; do not manually modify generated bindings.
- Keep FFI contracts small, typed and stable. Batch expensive boundary calls where beneficial.
- Avoid repeated JSON serialization and unnecessary data copying across the Rust/Kotlin boundary.
- Prevent panics from unwinding across foreign-language boundaries. Convert errors to appropriate typed bridge errors.
- Respect Android lifecycle, threading, permissions and background execution requirements.
- Preserve lock-screen controls, audio focus, background playback, notification behavior and queue consistency.
- Verify Rust changes are actually included in the Android build through the repository's existing native build pipeline; do not rely on stale native libraries.
6. Performance and reliability
- Prefer measurable improvements over speculative optimization. Do not claim performance gains without evidence.
- Avoid loading entire libraries into memory unnecessarily; use pagination, bounded caches and efficient queries where appropriate.
- Keep disk and network I/O off the UI thread; use timeouts and bounded retries.
- Prevent duplicate network requests, concurrent queue races, unbounded background work and resource leaks.
- Preserve battery efficiency and responsiveness. Do not introduce aggressive polling or busy loops.
- Keep persistent data compatible; handle failures without corrupting existing user data.
7. Scope and safety
- Read relevant code before editing. Make the smallest coherent change that satisfies the task.
- Do not modify unrelated files, redesign unrelated screens, rename modules or reformat the entire project.
- Do not remove features, change behavior or alter the public API unless required by the task.
- Do not fabricate build results, test results or benchmarks.
- Never commit secrets, credentials, signing keys or generated local configuration.
- If blocked, stop and report the actual problem. Do not bypass validation to make the task appear complete.
8. Repository workflow
- The Git repository is inside the plasmawave/ subdirectory, not the parent project directory. Run Git commands with git -C plasmawave ... or from inside plasmawave/.
- Before every task, run git status in the repository. If the working tree is not clean, stop and inform the user. Do not stash, reset, discard or commit existing changes automatically.
- Questions and explanations do not require changes, builds or commits.
- For code-changing tasks, run appropriate Rust compilation checks for affected crates using the existing workspace/toolchain (for example, cargo check where applicable).
- Run targeted Rust formatting checks and, where supported, targeted Clippy checks. Do not reformat unrelated files or apply broad automatic fixes. Compilation checks are allowed without approval; tests are not.
- After every code-changing task, run the project's assembleDebug build, including the appropriate native Rust build steps if not already integrated. If desktop code is affected, also run the applicable desktop compilation check.
- If any required compilation check or build fails, DO NOT COMMIT. Stop and explain the error to the user.
- Ask the user before running any tests, including testDebugUnitTest, cargo test, instrumentation tests, integration tests or test-running aliases. A debug build (assembleDebug) and ordinary compilation checks do not require approval. Inspect custom scripts for implicit test execution.
- After successful validation, inspect git diff --stat, the actual diff and git status --short, including untracked files. If any unrelated file has changed, stop and inform the user before staging or committing.
- For a successful code-changing task, run git add -A, inspect the staged diff, then create one local commit with a short, descriptive Turkish commit message.
- Local commits are not backups or publishing. Do not push, create remote releases or publish APKs without a user request or explicit approval.
- Never force-push, rewrite history, amend existing commits or perform destructive Git operations without explicit approval.
9. GitHub publication and Android APK
- When the user requests a GitHub update, publish an installable Android APK built from the same source commit as part of that update.
- Use the repository's scripts/publish-apk.sh workflow. Inspect it before running; do not execute unauthorized test or destructive operations.
- Include the actual Android version in the APK filename, such as projekt_b-d-0.4.apk. Do not invent or automatically bump the version unless required or approved.
- Ensure the README download link remains correct and points to an available release asset.
- Build from the exact intended commit using a clean working tree. Verify the APK asset and source commit correspondence before claiming success.
- If publication or APK generation fails, report the failure and any partially completed remote actions. Do not claim a successful release.
- Do not publish merely because a local commit was created.
10. Task completion report
Report in Turkish:
- What changed and why.
- Which logic is implemented in Rust and why any remaining Kotlin/Java code is necessary.
- Which compilation checks/builds actually ran and their results.
- Whether tests were run or skipped pending approval.
- Local commit hash and message, when applicable.
- GitHub/APK publication status, when applicable.
- Any limitations, risks or unverified behavior.
Never claim completion of an action that was not performed.
