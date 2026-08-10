# Web → Android porting checklist

Changes made to the web app that have **not** been applied to Android, recorded while the web
version is still being tested. Nothing here is done yet — this is the queue.

- **Web baseline:** `release/web` @ `df8ab68`
- **Android baseline:** `claude/session-m01nso` @ `6dc8e6f` (Aug 8)

> Note on branches: the `app/` tree on `release/web` and `main` is **stale** (last Android commit
> there is `09d152d`, v1.8). The live Android code is on `claude/session-m01nso`. Diff against
> that branch, not against whatever `app/` happens to sit next to `web/`.

Web commits in scope (newest first):

| Commit | Title |
|---|---|
| `df8ab68` | Raise the song-cooldown ceiling to 100 and reword the recommendation |
| `30d7217` | Make song cooldown a hard no-repeat guarantee, and deepen the discovery pool |
| `391886a` | Stop over-classifying artists as Tier A |
| `57a1da5` | Fix cooldown recommendation showing a stale artist pool |
| `ed24900` | Add artist list, playlist log and diagnostics exports |
| `90fcf3c` | Let users name their playlist |

`ba30091` ("Port the Android shuffle fixes to the web app") is excluded — it travelled the other
way and is already in Android.

---

## 1. Hard song cooldown — the behavioural one

**Priority: high.** This is the only item that changes what lands in a playlist. Until it is
done, a cooldown of *N* does not mean the same thing on the two platforms.

Android `TrueShuffleEngine.selectTrack` still ends both tier branches with `.ifEmpty { tracks }`,
so when an artist's tracks are all inside the cooldown window it hands back a **cooled-down
track anyway**. The setting is a preference there; on web it is now a guarantee.

`TrueShuffleEngine.kt` (~line 364, both branches):

```kotlin
// current — soft: falls back to the full list, reintroducing cooled-down tracks
val pool = if (isRareArtist) {
    tracks.filter { it.id !in cooldownTrackIds && it.id !in likedTrackIds }
        .ifEmpty { tracks.filter { it.id !in cooldownTrackIds } }
        .ifEmpty { tracks }
} else {
    tracks.filter { it.id !in cooldownTrackIds }.ifEmpty { tracks }
}
```

Port the web shape (`web/js/engine.js:selectTrack`): gate on cooldown **first**, return null when
nothing survives, and let the caller skip that artist.

```kotlin
// target — hard cooldown, soft liked-preference
private fun selectTrack(...): Track? {
    val fresh = tracks.filter { it.id !in cooldownTrackIds }
    if (fresh.isEmpty()) return null          // caller skips this artist
    val pool = if (isRareArtist) fresh.filter { it.id !in likedTrackIds }.ifEmpty { fresh }
               else fresh
    ...
}
```

Then at both call sites in `buildPlaylist` (first pass ~line 102, second pass ~line 113), skip on
null instead of adding:

```kotlin
val track = selectTrack(...) ?: continue
```

The liked-track preference stays soft — only the cooldown becomes hard.

**Why an artist running dry is safe:** the build skips that artist and the next one fills the
slot. It only comes up short when the *whole* artist list is exhausted. Verified on web by
simulation: a 298-artist library at cooldown 100 over 100 builds produced 0 repeats and 0 short
builds.

## 2. Report short builds instead of hiding them

`buildPlaylist` currently returns `List<Track>`. Web now returns `shortOfTarget` and `durationMs`
alongside the tracks, and the success screen shows a notice when the hard cooldown left too few
eligible tracks to fill the target.

Needs a return-type change (`List<Track>` → a small result class, or add the fields to whatever
the ViewModel already passes around) plus a notice in `HomeScreen`. Do this **with** item 1 —
a hard cooldown without this just silently yields short playlists.

## 3. Song-cooldown ceiling: 50 → 100

Android already sits at 50, so this is a smaller step than it was on web.

- `ShuffleHistoryStorage.kt:16` — `MAX_STORED = 50` → `100`
- `SettingsSheet.kt:107-108` — `valueRange = 1f..50f, steps = 48` → `1f..100f, steps = 98`

Check the storage cost before committing: Android persists history via Gson to SharedPreferences,
which has no 5 MB browser quota but is not meant for large blobs either. Entries are ID lists
only (~1.6 KB per build on web), so 100 builds ≈ 160 KB. If that reads as too much for prefs,
move the history to a file rather than capping the setting.

Artist cooldown is already `1f..30f` and matches web. No change.

## 4. Song-cooldown recommendation

Android has `maxSustainableCooldown` (artists) but no song-side equivalent. Port from
`web/js/engine.js`:

```kotlin
fun maxSustainableSongCooldown(totalTrackCount: Int, targetDurationMs: Long): Int {
    val tracksPerBuild = maxOf(1, (targetDurationMs / AVG_TRACK_MS).toInt())
    return maxOf(0, totalTrackCount / tracksPerBuild)
}
```

Validated on web — the boundary lands exactly where it predicts (a 3200-track pool, ceiling 106,
stays clean at cooldown 100; a 2400-track pool, ceiling 80, first goes short on build 76).

Needs a `lastTrackPoolSize` recorded on each build (Android already stores `lastArtistPoolSize`
for the artist recommendation — mirror it), then the recommendation text under the song slider.

Use the **reworded** wording from `df8ab68`, not the original. Leading with "Recommended: N" read
as a warning even when the user was well inside budget:

- in budget → `Your {pool} tracks support up to {rec} builds with no song repeating — you're at {chosen}.`
- over budget → `Your {pool} tracks cover about {rec} builds. A song never repeats inside your window, so at {chosen} the last builds start coming up short.`

## 5. Custom playlist name

Web commit `90fcf3c`. Android `AppSettings` has no playlist-name key — every build overwrites a
fixed-name playlist. Needs: a setting, a text field in `SettingsSheet`, use at create time, and
the same fallback-to-default behaviour when blank.

---

## Already correct on Android — do not "port"

Checked against `claude/session-m01nso`; these web commits were the web catching up, not Android
falling behind.

- **Tier A narrowing** (`391886a`) — `SpotifyRepository.getTopArtists` already uses `long_term` +
  `medium_term` at limit 50, no `short_term`, no pagination past 50. Web had drifted to three
  ranges × 100, which classified over half a 298-artist library as "top" and starved Tier B.
- **Discovery pool depth** (`30d7217`) — web raised `GAP_TRACKS_PER_ARTIST` to 25; Android already
  stores `.shuffled().take(40)`, which is deeper.
- **Exports** (`ed24900`) — artist list, diagnostics *and* playlist log are all already wired in
  `SettingsSheet` (~lines 367, 390, 413).
- **Stale settings panel** (`57a1da5`) — web-only bug. `loadSettingsUI()` ran once at page load,
  so the panel showed figures frozen from then; fixed with a `settingsRefreshers` list. Compose
  recomposes from `StateFlow` already, so there is nothing to port.
- **Quota-aware `save()` / `storageReport()`** (`30d7217`) — browser-specific. Android has no
  `QuotaExceededError` equivalent. Only revisit if item 3 pushes history out of SharedPreferences.

---

## Suggested order

1. Items 1 + 2 together (hard cooldown + short-build reporting) — one behavioural change, needs a
   device build and a couple of real playlists to confirm.
2. Item 3 (ceiling), after checking the SharedPreferences size question.
3. Item 4 (recommendation), which depends on 3 for its clamp.
4. Item 5 (playlist name) — independent, can go any time.
