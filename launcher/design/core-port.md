# Native core port

`bscp-launcher-core` is a synchronous Rust library. It does not invoke .NET or a WebView. Run file and HTTP operations on the application worker, outside Slint's UI thread.

Public modules: storage, worlds, setup, profile, updates, diagnostics. All APIs are re-exported from the crate root. Errors use `anyhow::Result` and carry stable operation codes before the description. Existing schema-1 JSON writes PascalCase and accepts PascalCase, lowerCamelCase and Rust snake_case on DTO fields; file inventories retain their literal key paths.

## Integration

- `Layout::new(root)`, `init()`, `load()`, `save(&Settings)`, `data(&Settings)`, `bundle(&Settings, "client" | "server")`, `pipe()`.
- `Setup::discover(root) -> BundleLocation`; `Setup::check_components(&client, &server)`; `Setup::complete(&layout, proposed, &map, name, progress)`.
- Cancellation: `Setup::complete_cancellable(..., &AtomicBool)`, `WorldStore::create_cancellable(name, &map, owner, &AtomicBool)`, and `BundleInstaller::install_cancellable(&layout, &data, progress, local_archive, Arc<AtomicBool>)`. The UI sets this flag directly. `UpdateService::new(&layout)?.with_cancel(flag)` offers check/download/install/rollback. Download, map hashing/copying, and extraction check cancellation between chunks. A stalled socket read is bounded by a 20-second read timeout (20-second connect timeout, 20-minute overall transfer timeout); partial downloads remain resumable.
- `WorldStore::new(data)?` creates the managed worlds root. `folder/get/save/list/map/lease/supervisor_lease/create/clone_world/rename/delete/export/import/restore/automatic_backup` implement lifecycle and persistence.
- `ClientProfile::lease(&data)` remains held for the client lifecycle; `prepare(&data, &bundle)` preserves modified user mods and tracks launcher-installed mods.
- `LocalIdentity::load(&layout)` retains existing C# P256 DER keys and creates compatible PKCS8/SPKI keys if missing. Signatures use fixed-width 64-byte IEEE P1363 encoding, matching C# default ECDSA. The identity directory and profile use protected owner-only Windows ACLs.
- `Reports::create(&layout, description, include_performance)` returns the diagnostic ZIP. JFR data requires explicit opt-in. Text is redacted, per-file and total payload budgets are bounded.

## Compatibility and safety

World base digests follow the Windows C# implementation exactly: sort original relative paths using UTF16 ordinal order (including Windows backslash separators), then hash the normalized slash UTF8 name prefixed by little-endian i32 byte length, little-endian i64 file size, and contents. Managed map bases are copied, never hardlinked. Original game resources are read only. Blank data paths are rejected before they can resolve to the launcher root.

World locks live outside world directories at `.locks/<id>.lock`; native `.server-lease` and `.client-lease` files are probed before operations. Restore journals preserve the distinction between prepared (rollback) and committed (finish) phases; recovery does not invent empty world data. Valid empty-world exports remain importable.

JSON persistence flushes a newly created temporary file and uses Windows ReplaceFileW with a `.bak` (or MoveFileExW for the first write). Corrupt current JSON falls back to its backup. Paths reject traversal, drive/ADS prefixes, trailing dots/spaces, reserved Windows device names, and reparse points. ZIP extraction adds case-insensitive duplicate detection, link rejection, entry count and size budgets, and create-new writes.

Updates authenticate raw manifests with RSA PKCS#1 v1.5 / SHA256, then verify exact package inventories and every file hash. External file indexes are themselves pinned. Rollback authenticates the saved raw manifest and contents before changing the pointer, and preserves the highest accepted sequence. Bootstrap bytes and download pins are compiled from the existing `src/Core/bootstrap-pins.json` without regenerating that file. Redirects are checked at each step against the GitHub HTTPS allowlist.

## Verification

`cargo test -p bscp-launcher-core`: 21 tests passed on Windows MSVC. Tests cover atomic JSON/backup migration, exact digest framing, immutable base copying, credentials excluded from clone, exclusive supervisor/native locks, round-trip export/restore, prepared/committed crash recovery, failed recovery preserving absent data, empty-world import, ZIP traversal/duplicates/budgets, player-mod conflict protection, exact inventories, persisted P256 signing, cancellation before commit, diagnostic redaction/JFR opt-in, embedded pins, download origin restrictions, and RSA-authenticated rollback with tamper detection and monotonic sequence retention.

Only temporary fixtures created by these tests were cleaned. Existing launcher/game/server/archive data was not modified by core tests.

