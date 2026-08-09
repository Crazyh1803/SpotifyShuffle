// storage.js — localStorage persistence for settings, tokens, cache, and history

const KEYS = {
    settings: 'trueshuffle_settings',
    tokens:   'trueshuffle_tokens',
    gapCache: 'trueshuffle_gap_cache',
    history:  'trueshuffle_history',
    playlistId: 'trueshuffle_playlist_id',
};

function load(key, defaults) {
    try {
        const raw = localStorage.getItem(key);
        return raw ? { ...defaults, ...JSON.parse(raw) } : { ...defaults };
    } catch {
        return { ...defaults };
    }
}

function save(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); } catch { /* quota exceeded — ignore */ }
}

// ── Settings ─────────────────────────────────────────────────────────────────

const SETTINGS_DEFAULTS = {
    clientId: '',
    discoveryBias: 60,
    playlistDurationMs: 2 * 60 * 60 * 1000,  // 2 hours
    /** Song/track cooldown: how many past playlists a TRACK skips before it's eligible again. */
    cooldownPlaylists: 5,
    /** Artist cooldown: how many past playlists an ARTIST skips. Independent of the song value. */
    artistCooldownPlaylists: 5,
    likedSongsExploreMode: false,
    /**
     * Artists from the last build that could actually contribute a track. Persisted so Settings
     * can show the cooldown recommendation without re-running a full library scan.
     */
    lastArtistPoolSize: 0,
};

export const settings = {
    get: () => load(KEYS.settings, SETTINGS_DEFAULTS),
    save: (partial) => save(KEYS.settings, { ...settings.get(), ...partial }),
};

// ── Tokens ───────────────────────────────────────────────────────────────────

export const tokens = {
    get: () => load(KEYS.tokens, {}),
    save: (data) => save(KEYS.tokens, data),
    clear: () => localStorage.removeItem(KEYS.tokens),
    isLoggedIn: () => {
        const t = tokens.get();
        return !!(t.accessToken && t.refreshToken);
    },
};

// ── Gap Artist Cache ──────────────────────────────────────────────────────────
// Structure: { [artistId]: { tracks: Track[], scannedAtMs: number } }

export const gapCache = {
    get: () => load(KEYS.gapCache, {}),
    save: (cache) => save(KEYS.gapCache, cache),
    clear: () => localStorage.removeItem(KEYS.gapCache),
    clearTimestamps: () => {
        const cache = gapCache.get();
        const reset = {};
        for (const [id, entry] of Object.entries(cache)) {
            reset[id] = { ...entry, scannedAtMs: 0 };
        }
        gapCache.save(reset);
    },
};

// ── Playlist ID ───────────────────────────────────────────────────────────────

export const playlistId = {
    get: () => localStorage.getItem(KEYS.playlistId) || null,
    save: (id) => localStorage.setItem(KEYS.playlistId, id),
    clear: () => localStorage.removeItem(KEYS.playlistId),
};

// ── History / Cooldown ────────────────────────────────────────────────────────
// Structure: { playlists: [ { trackIds: string[], artistIds: string[] } ] }, newest first.

/** Playlist snapshots retained. Must be ≥ the largest cooldown setting (song cooldown, 50). */
const MAX_STORED = 50;

export const history = {
    get: () => load(KEYS.history, { playlists: [] }),
    save: (data) => save(KEYS.history, data),
    clear: () => localStorage.removeItem(KEYS.history),

    record(tracks) {
        const h = history.get();
        const entry = {
            trackIds: tracks.map(t => t.id),
            artistIds: [...new Set(tracks.flatMap(t => t.artists.map(a => a.id)))],
        };
        h.playlists.unshift(entry);
        // Must cover the largest song-cooldown setting (50); each entry is only ID lists.
        if (h.playlists.length > MAX_STORED) h.playlists = h.playlists.slice(0, MAX_STORED);
        history.save(h);
    },

    /** Track IDs from the last [n] playlists — the song cooldown set. */
    getCooldownTrackIds(n) {
        const recent = history.get().playlists.slice(0, n);
        return new Set(recent.flatMap(p => p.trackIds));
    },

    /**
     * Artist IDs from the last [n] playlists as one Set per playlist, MOST-RECENT FIRST.
     * The engine needs them separated (not flattened) so its adaptive cooldown can drop whole
     * playlists off the back when suppressing them all would starve the artist pool.
     */
    getRecentArtistSets(n) {
        return history.get().playlists.slice(0, n).map(p => new Set(p.artistIds));
    },
};

// ── Full clear (logout) ───────────────────────────────────────────────────────

export function clearAll() {
    tokens.clear();
    gapCache.clear();
    history.clear();
    playlistId.clear();
    // Keep settings (client ID, preferences)
}
