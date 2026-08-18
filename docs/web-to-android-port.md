# Web ↔ Android parity

Status of feature and correctness parity between the two clients.

- **Web:** `release/web`
- **Android:** `claude/session-m01nso` (the `app/` tree on `release/web` and `main` is **stale**
  at v1.8 — diff against `claude/session-m01nso`, not against whatever sits next to `web/`)

## Done — Aug 2026 (round 9)

Android `3a93d85`, web `9d647da`. Driven by a review of 14 Android builds that found the song
cooldown was barely functioning.

| Item | Where |
|---|---|
| Hard song cooldown (was `.ifEmpty { tracks }`) | Android |
| `shortOfTarget` / `durationMs` + success-screen notice | Android |
| Mutually exclusive tiers (C > A > B) + pass-1 dedup guard | Android |
| Removed the dead popularity bias | Android |
| App version, cooldown-history depth, 429 tally in diagnostics | Both |
| Song-cooldown ceiling 50 → 100 (setting **and** `MAX_STORED`) | Android |
| Corrected recommendation formula + reworded text | Android |
| Custom playlist name, with in-place rename | Android |
| Liked-songs explore toggle (ported from `release/v2.0`) | Android |
| Automatic gap-artist rescan interval | Web |
| `of which stale` in diagnostics | Web |
| JVM regression tests for the engine | Android |

The Android tests live in `app/src/test/java/.../TrueShuffleEngineTest.kt`. They drive many
consecutive builds and thread history through as the real callers do — neither defect shows up
in a single build. Against an engine with the defects reintroduced they report **422** cooldown
violations and duplicates in **5/60** and **23/60** builds.

## Still open

| Capability | Web | Android | Note |
|---|---|---|---|
| Auto-rebuild (background) | ❌ | ✅ | Declined — no clean browser equivalent |
| Album / release metadata | 58% | 100% | Web's older gap-cache entries lack it |
| Discovery bias label | ⚠️ | ⚠️ | "90%" yields ~50% Tier C; the number is a weight, not a percentage. Deferred |
| Stalled discovery scan | — | ⚠️ | Diagnosed, not fixed — see below |

### The stalled Android scan

The Aug 18 diagnostics showed `240 / 298`, `Complete: false`, library last refreshed three weeks
earlier: 154 artists never attempted, 84 stuck in retry, 60 scanned. `BATCH_SIZE = 60` should
clear that in ~4 builds; 14 builds later it had not moved.

The cause could not be determined from the export, which is why the 429 tally and version lines
were added. **Next step:** one device build on the new version, then read `--- Rate Limiting ---`
and `--- Cooldown History ---` from a fresh export.

## Verification still owed

Everything Android was verified by JVM unit tests and code review only — there is no Android SDK
in this build environment, so **none of the Android UI or repository changes have been compiled**.
A device build is required before trusting: the Settings sheet (playlist-name field, explore
toggle, 1–100 slider), the rename call, the short-build notice, and the new diagnostics lines.
