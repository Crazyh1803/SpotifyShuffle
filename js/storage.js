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
    cooldownPlaylists: 2,
    artistCooldownPlaylists: 2,
    likedSongsExploreMode: false,
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
// Structure: { playlists: [ { trackIds: string[], artistIds: string[] } ] }

export const history = {
    get: () => load(KEYS.history, { playlists: [] }),
    save: (data) => save(KEYS.history, data),
    clear: () => localStorage.removeItem(KEYS.history),

    record(tracks, cooldownN) {
        const h = history.get();
        const entry = {
            trackIds: tracks.map(t => t.id),
            artistIds: [...new Set(tracks.flatMap(t => t.artists.map(a => a.id)))],
        };
        h.playlists.unshift(entry);
        if (h.playlists.length > 20) h.playlists = h.playlists.slice(0, 20); // cap at 20
        history.save(h);
    },

    getCooldownSets(cooldownN) {
        const h = history.get();
        const recent = h.playlists.slice(0, cooldownN);
        const trackIds = new Set(recent.flatMap(p => p.trackIds));
        const artistIds = new Set(recent.flatMap(p => p.artistIds));
        return { trackIds, artistIds };
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
