// engine.js — True Shuffle algorithm
// Direct translation of TrueShuffleEngine.kt. Keep the two in sync: this file and the Kotlin
// engine are the same algorithm, and every divergence between them has shown up as a bug.
//
// Tier system:
//   A — the user's top artists (frequently heard; included but deprioritised)
//   B — followed artists with library tracks but NOT top artists (familiar)
//   C — "discovery": followed artists whose tracks came only from the gap-fill scan
//
// The B:A ratio is fixed at 4:1. Only the C count varies with the discovery bias slider.

/** Tracks shorter than this are fragments/interstitials — filtered out. */
const MIN_TRACK_MS = 60 * 1000;

/**
 * Tracks longer than this are outliers (silence/joke tracks, sprawling live jams) — a single
 * 30-minute track would blow the duration budget. Set generously so prog/post-rock epics survive.
 */
const MAX_TRACK_MS = 20 * 60 * 1000;

/** Rough average track length, used to estimate how many artists a build needs. */
const AVG_TRACK_MS = 240 * 1000;

/** Keep at least neededArtists × this many fresh artists before applying cooldown. */
const FRESH_HEADROOM = 2;

/**
 * Effective popularity assigned to unknown (0) tracks so they sort as *average* rather than as
 * the deepest cut. Album- and gap-sourced tracks have no popularity field and default to 0;
 * without this they always win the least-popular bias over tracks with real, genuinely-low
 * popularity, and the deep-cut preference degenerates into "always pick the most obscure cut".
 */
const NEUTRAL_POPULARITY = 35;

const NOISE_WORDS = [
    'skit', 'interlude', 'intro', 'outro', 'reprise',
    'spoken word', 'spoken', 'transition', 'commentary',
];

// Matched as whole words so "Interlude" doesn't catch "Prelude to a Kiss" and "intro" doesn't
// catch "Introspection". Substring matching (the previous behaviour here) over-filtered badly.
const NOISE_PATTERNS = NOISE_WORDS.map(
    w => new RegExp(`\\b${w.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\b`)
);

function isNonMusicTrack(name) {
    const lower = (name || '').toLowerCase();
    return NOISE_PATTERNS.some(re => re.test(lower));
}

/**
 * Estimates the largest artist-cooldown value (in playlists) the given pool can fully sustain
 * before adaptiveArtistCooldown's starvation floor starts relaxing it early. Mirrors the same
 * math the engine actually uses, so it's precise rather than a guess.
 */
export function maxSustainableCooldown(poolArtistCount, targetDurationMs) {
    const neededArtists = Math.max(1, Math.floor(targetDurationMs / AVG_TRACK_MS));
    const floor = neededArtists * FRESH_HEADROOM;
    return Math.max(0, Math.floor((poolArtistCount - floor) / neededArtists));
}

/**
 * Classifies a track into "A", "B" or "C" with priority C > A > B. Checks ALL of a track's
 * artists, not just the primary — gap-fill tracks sometimes list a featured artist first, which
 * would otherwise misclassify a discovery track as Tier B.
 */
export function tierOf(track, discoveryIds, topIds) {
    const artists = track.artists || [];
    if (artists.some(a => discoveryIds.has(a.id))) return 'C';
    if (artists.some(a => topIds.has(a.id)))       return 'A';
    return 'B';
}

// Returns per-cycle counts {c, b, a} for each tier
function computeTierWeights(bias) {
    if (bias <= 15) return { c: 0, b: 4, a: 1 };
    if (bias <= 35) return { c: 1, b: 4, a: 1 };
    if (bias <= 55) return { c: 2, b: 4, a: 1 };
    if (bias <= 75) return { c: 3, b: 4, a: 1 };
    if (bias <= 90) return { c: 5, b: 4, a: 1 };
    return { c: 9, b: 4, a: 1 };
}

function shuffle(arr) {
    const a = [...arr];
    for (let i = a.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [a[i], a[j]] = [a[j], a[i]];
    }
    return a;
}

function interleave2(tierB, tierA) {
    const result = [];
    let bi = 0, ai = 0;
    while (bi < tierB.length || ai < tierA.length) {
        for (let i = 0; i < 4 && bi < tierB.length; i++) result.push(tierB[bi++]);
        if (ai < tierA.length) result.push(tierA[ai++]);
    }
    return result;
}

function interleave3(tierC, tierB, tierA, w) {
    const result = [];
    let ci = 0, bi = 0, ai = 0;
    while (ci < tierC.length || bi < tierB.length || ai < tierA.length) {
        for (let i = 0; i < w.c && ci < tierC.length; i++) result.push(tierC[ci++]);
        for (let i = 0; i < w.b && bi < tierB.length; i++) result.push(tierB[bi++]);
        for (let i = 0; i < w.a && ai < tierA.length; i++) result.push(tierA[ai++]);
    }
    return result;
}

function buildOrderedArtists(artists, topIds, discoveryIds, bias) {
    const s = shuffle(artists);
    const tierA = s.filter(a => topIds.has(a.id));
    const tierC = s.filter(a => discoveryIds.has(a.id));
    const tierB = s.filter(a => !topIds.has(a.id) && !discoveryIds.has(a.id));

    if (tierC.length === 0 || bias <= 15) return interleave2(tierB, tierA);
    return interleave3(tierC, tierB, tierA, computeTierWeights(bias));
}

/**
 * Decides which artists to place on cooldown for this build.
 *
 * Walks recentArtistSets most-recent-first, adding each past playlist's artists to the suppressed
 * set — but stops before the remaining fresh pool would drop below neededArtists × FRESH_HEADROOM.
 * Applies to all tiers including Tier C: the floor check is tier-agnostic pool-size math, so a
 * healthy discovery pool is protected the same way A/B already were.
 *
 * With a large library this behaves like a fixed-N cooldown; with a small one it relaxes
 * automatically so a playlist can still be built, at the cost of some repeats.
 */
function adaptiveArtistCooldown(recentArtistSets, poolArtistIds, neededArtists) {
    const floor = neededArtists * FRESH_HEADROOM;
    const suppressed = new Set();

    for (const set of recentArtistSets) {
        const additions = [...set].filter(id => poolArtistIds.has(id) && !suppressed.has(id));
        // Stop at playlist granularity once suppressing this one would starve the pool.
        if (poolArtistIds.size - (suppressed.size + additions.length) < floor) break;
        for (const id of additions) suppressed.add(id);
    }
    return suppressed;
}

/**
 * Selects one track for an artist using a three-level preference:
 *   1. fresh + non-liked, 2. fresh only, 3. everything (last resort)
 *
 * isRareArtist (Tier B/C) prefers non-liked tracks so album deep cuts surface. Tier A skips that
 * filter — top artists are expected favourites and filtering liked tracks would leave very little.
 * Both then apply an x² bias over ascending effective popularity.
 */
function selectTrack(tracks, isRareArtist, likedIds, cooldownIds) {
    const notCooldown = (t) => !cooldownIds.has(t.id);
    const notLiked    = (t) => !likedIds.has(t.id);

    let pool = tracks.filter(t => notCooldown(t) && (isRareArtist ? notLiked(t) : true));
    if (pool.length === 0) pool = tracks.filter(notCooldown);
    if (pool.length === 0) pool = [...tracks];

    if (pool.length === 1) return pool[0];

    const effectivePop = (t) => {
        const p = t.popularity ?? 0;
        return p <= 0 ? NEUTRAL_POPULARITY : p;
    };
    const sorted = [...pool].sort((a, b) => effectivePop(a) - effectivePop(b));

    // When every track shares the same effective popularity (common when a whole pool is
    // album-sourced unknowns) the x² index would cluster at the first entry — use uniform random.
    if (effectivePop(sorted[0]) === effectivePop(sorted[sorted.length - 1])) {
        return pool[Math.floor(Math.random() * pool.length)];
    }

    // x² biases toward index 0 (least popular = deepest cut).
    const r = Math.random();
    return sorted[Math.min(sorted.length - 1, Math.floor(r * r * sorted.length))];
}

/**
 * Builds a shuffled playlist.
 *
 * @param {Object} params
 * @param {Array}  params.followedArtists  - List of Artist objects (id, name)
 * @param {Set}    params.topIds           - Top-artist IDs (Tier A)
 * @param {Object} params.tracksByArtist   - { artistId: Track[] }
 * @param {Set}    params.discoveryIds     - Discovery artist IDs (Tier C)
 * @param {Set}    params.likedIds         - Liked track IDs
 * @param {Set}    params.cooldownTrackIds - Track IDs from the last N playlists (song cooldown)
 * @param {Array<Set>} params.recentArtistSets - Artist IDs per past playlist, MOST-RECENT FIRST.
 *                     Feeds the adaptive artist cooldown; pass [] to disable artist cooldown.
 * @param {number} params.discoveryBias    - 0–100
 * @param {number} params.targetDurationMs - Target playlist length in ms
 * @returns {{ tracks: Track[], tierACount, tierBCount, tierCCount, artistCooldownApplied }}
 */
export function buildPlaylist({
    followedArtists, topIds, tracksByArtist, discoveryIds,
    likedIds, cooldownTrackIds, recentArtistSets = [], discoveryBias, targetDurationMs,
}) {
    // Filter non-music tracks AND duration outliers — very short fragments and absurdly long
    // tracks. Falls back progressively so an artist is never emptied purely by filtering.
    const filtered = {};
    for (const [artistId, tracks] of Object.entries(tracksByArtist)) {
        const strict = tracks.filter(t => {
            const ms = t.duration_ms ?? 0;
            return !isNonMusicTrack(t.name) && ms >= MIN_TRACK_MS && ms <= MAX_TRACK_MS;
        });
        const loose = strict.length > 0 ? strict : tracks.filter(t => !isNonMusicTrack(t.name));
        filtered[artistId] = loose.length > 0 ? loose : tracks;
    }

    const artistsWithTracks = followedArtists.filter(a => filtered[a.id]?.length > 0);
    if (artistsWithTracks.length === 0) {
        return { tracks: [], tierACount: 0, tierBCount: 0, tierCCount: 0, artistCooldownApplied: 0 };
    }

    // Adaptive artist cooldown: suppress artists from the most-recent playlists first (all
    // tiers), stopping before the fresh pool would starve. Self-relaxing, so a small library
    // never gets stuck unable to build.
    const poolArtistIds = new Set(artistsWithTracks.map(a => a.id));
    const neededArtists = Math.max(1, Math.floor(targetDurationMs / AVG_TRACK_MS));
    const cooldownArtistIds = adaptiveArtistCooldown(recentArtistSets, poolArtistIds, neededArtists);

    // Artists on cooldown go after all fresh artists, so they only fill in if the playlist
    // would otherwise fall short of the target duration.
    const freshArtists     = artistsWithTracks.filter(a => !cooldownArtistIds.has(a.id));
    const cooldownFallback = shuffle(artistsWithTracks.filter(a => cooldownArtistIds.has(a.id)));

    const orderedArtists = [
        ...buildOrderedArtists(freshArtists, topIds, discoveryIds, discoveryBias),
        ...cooldownFallback,
    ];

    const playlist = [];
    let totalMs = 0;

    // Pass 1: one track per artist
    for (const artist of orderedArtists) {
        if (totalMs >= targetDurationMs) break;
        const tracks = filtered[artist.id];
        if (!tracks) continue;

        const track = selectTrack(tracks, !topIds.has(artist.id), likedIds, cooldownTrackIds);
        playlist.push(track);
        totalMs += track.duration_ms ?? 0;
    }

    // Pass 2: still short — allow repeat artists, but keep honouring cooldown/liked/popularity
    // via selectTrack rather than a blind random pick, so we don't re-surface recently played
    // tracks just to fill time.
    if (totalMs < targetDurationMs) {
        const usedIds = new Set(playlist.map(t => t.id));
        for (const artist of shuffle(orderedArtists)) {
            if (totalMs >= targetDurationMs) break;
            const available = (filtered[artist.id] || []).filter(t => !usedIds.has(t.id));
            if (available.length === 0) continue;

            const track = selectTrack(available, !topIds.has(artist.id), likedIds, cooldownTrackIds);
            playlist.push(track);
            usedIds.add(track.id);
            totalMs += track.duration_ms ?? 0;
        }
    }

    // Count tiers from the tracks themselves (checking every credited artist), matching the
    // Kotlin engine — counting by slot artist misattributes tracks that lead with a feature.
    let tierACount = 0, tierBCount = 0, tierCCount = 0;
    for (const t of playlist) {
        const tier = tierOf(t, discoveryIds, topIds);
        if (tier === 'C') tierCCount++;
        else if (tier === 'A') tierACount++;
        else tierBCount++;
    }

    return {
        tracks: playlist,
        tierACount, tierBCount, tierCCount,
        artistCooldownApplied: cooldownArtistIds.size,
    };
}
