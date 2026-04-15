// engine.js — True Shuffle algorithm
// Direct translation of TrueShuffleEngine.kt — same tier logic, same x² bias.

const NON_MUSIC_KEYWORDS = [
    'skit', 'interlude', 'intro', 'outro', 'spoken word', 'commentary',
    'bonus track', 'hidden track', 'reprise',
];

function isNonMusicTrack(name) {
    const lower = name.toLowerCase();
    return NON_MUSIC_KEYWORDS.some(kw => lower.includes(kw));
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

// x² popularity bias: prefers low-popularity (deep cut) tracks.
// isRareArtist = true → also avoids tracks already in liked songs (surfaces album cuts).
function selectTrack(tracks, isRareArtist, likedIds, cooldownIds) {
    const notCooldown = (t) => !cooldownIds.has(t.id);
    const notLiked    = (t) => !likedIds.has(t.id);

    let pool = tracks.filter(t => notCooldown(t) && (isRareArtist ? notLiked(t) : true));
    if (pool.length === 0) pool = tracks.filter(notCooldown);
    if (pool.length === 0) pool = [...tracks];

    // Sort ascending by popularity (deep cuts first)
    pool.sort((a, b) => (a.popularity ?? 0) - (b.popularity ?? 0));

    const allSame = pool.every(t => (t.popularity ?? 0) === (pool[0].popularity ?? 0));
    if (allSame) return pool[Math.floor(Math.random() * pool.length)];

    // x² bias: Math.random()² heavily favors indices near 0
    const r = Math.random();
    return pool[Math.floor(r * r * pool.length)];
}

/**
 * Builds a shuffled playlist.
 *
 * @param {Object} params
 * @param {Array}  params.followedArtists   - List of Artist objects (id, name)
 * @param {Set}    params.topIds            - Set of top-artist IDs (Tier A)
 * @param {Object} params.tracksByArtist    - { artistId: Track[] }
 * @param {Set}    params.discoveryIds      - Set of discovery artist IDs (Tier C)
 * @param {Set}    params.likedIds          - Set of liked track IDs
 * @param {Set}    params.cooldownTrackIds  - Track IDs from recent playlists
 * @param {Set}    params.cooldownArtistIds - Artist IDs from recent playlists
 * @param {number} params.discoveryBias     - 0–100
 * @param {number} params.targetDurationMs  - Target playlist length in ms
 * @returns {{ tracks: Track[], tierACount: number, tierBCount: number, tierCCount: number }}
 */
export function buildPlaylist({
    followedArtists, topIds, tracksByArtist, discoveryIds,
    likedIds, cooldownTrackIds, cooldownArtistIds, discoveryBias, targetDurationMs,
}) {
    // Filter non-music tracks per artist; keep unfiltered list if filtering empties it
    const filtered = {};
    for (const [artistId, tracks] of Object.entries(tracksByArtist)) {
        const f = tracks.filter(t => !isNonMusicTrack(t.name));
        filtered[artistId] = f.length > 0 ? f : tracks;
    }

    const artistsWithTracks = followedArtists.filter(a => filtered[a.id]?.length > 0);
    if (artistsWithTracks.length === 0) {
        return { tracks: [], tierACount: 0, tierBCount: 0, tierCCount: 0 };
    }

    const cooldownArtistSet = new Set(cooldownArtistIds);
    const freshArtists      = artistsWithTracks.filter(a => !cooldownArtistSet.has(a.id));
    const cooldownFallback  = shuffle(artistsWithTracks.filter(a => cooldownArtistSet.has(a.id)));

    const orderedArtists = [
        ...buildOrderedArtists(freshArtists, topIds, discoveryIds, discoveryBias),
        ...cooldownFallback,
    ];

    const playlist   = [];
    let totalMs      = 0;
    let tierACount   = 0, tierBCount = 0, tierCCount = 0;

    // Pass 1: one track per artist
    for (const artist of orderedArtists) {
        if (totalMs >= targetDurationMs) break;
        const tracks = filtered[artist.id];
        if (!tracks) continue;

        const track = selectTrack(tracks, !topIds.has(artist.id), likedIds, cooldownTrackIds);
        playlist.push(track);
        totalMs += track.duration_ms ?? 0;

        if (discoveryIds.has(artist.id))    tierCCount++;
        else if (topIds.has(artist.id))     tierACount++;
        else                                tierBCount++;
    }

    // Pass 2: fill to target duration with additional tracks (no repeats)
    if (totalMs < targetDurationMs) {
        const usedIds       = new Set(playlist.map(t => t.id));
        const shuffledExtra = shuffle(artistsWithTracks);
        const emptyCooldown = new Set();

        for (const artist of shuffledExtra) {
            if (totalMs >= targetDurationMs) break;
            const available = (filtered[artist.id] || []).filter(t => !usedIds.has(t.id));
            if (available.length === 0) continue;

            const track = selectTrack(available, !topIds.has(artist.id), likedIds, emptyCooldown);
            playlist.push(track);
            usedIds.add(track.id);
            totalMs += track.duration_ms ?? 0;
        }
    }

    return { tracks: playlist, tierACount, tierBCount, tierCCount };
}
