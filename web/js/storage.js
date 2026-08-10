// storage.js — localStorage persistence for settings, tokens, cache, and history

const KEYS = {
    settings: 'trueshuffle_settings',
    tokens:   'trueshuffle_tokens',
    gapCache: 'trueshuffle_gap_cache',
    history:  'trueshuffle_history',
    playlistId: 'trueshuffle_playlist_id',
    artistLibrary: 'trueshuffle_artist_library',
    playlistLog:   'trueshuffle_playlist_log',
};

function load(key, defaults) {
    try {
        const raw = localStorage.getItem(key);
        return raw ? { ...defaults, ...JSON.parse(raw) } : { ...defaults };
    } catch {
        return { ...defaults };
    }
}

/** Records the most recent quota failure so diagnostics can report it instead of hiding it. */
let lastQuotaError = null;

/** @returns true if the write landed, false if storage rejected it (usually quota). */
function save(key, value) {
    try {
        localStorage.setItem(key, JSON.stringify(value));
        return true;
    } catch (e) {
        lastQuotaError = { key, at: Date.now(), message: e?.message ?? String(e) };
        console.warn(`[storage] write to "${key}" failed:`, e?.message ?? e);
        return false;
    }
}

/** Approximate bytes currently used by this app's keys, plus any recent quota failure. */
export function storageReport() {
    let bytes = 0;
    for (const key of Object.values(KEYS)) {
        bytes += (localStorage.getItem(key) || '').length;
    }
    return { bytes, lastQuotaError };
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
    /** Name given to the playlist created on Spotify. Blank falls back to DEFAULT_PLAYLIST_NAME. */
    playlistName: '',
    /**
     * The name actually pushed to Spotify on the last successful build. Lets a build skip the
     * rename API call unless the user has changed the name since — one fewer request per build,
     * which matters given how easily this app hits Spotify's rate limit.
     */
    appliedPlaylistName: '',
    /**
     * Artists from the last build that could actually contribute a track. Persisted so Settings
     * can show the cooldown recommendation without re-running a full library scan.
     */
    lastArtistPoolSize: 0,
    /** Distinct tracks across the pool on the last build — drives the song-cooldown ceiling. */
    lastTrackPoolSize: 0,
};

/** Used whenever the user hasn't set a name of their own. */
export const DEFAULT_PLAYLIST_NAME = 'True Shuffle';

/** Spotify rejects excessively long playlist names; keep well inside its limit. */
export const MAX_PLAYLIST_NAME_LEN = 100;

export const settings = {
    get: () => load(KEYS.settings, SETTINGS_DEFAULTS),
    save: (partial) => save(KEYS.settings, { ...settings.get(), ...partial }),

    /**
     * The playlist name to send to Spotify: trimmed, length-capped, and falling back to the
     * default when blank. Centralised so the build path and the Settings UI can't disagree
     * about what an empty or over-long entry means.
     */
    resolvedPlaylistName() {
        const raw = (settings.get().playlistName || '').trim();
        return raw ? raw.slice(0, MAX_PLAYLIST_NAME_LEN) : DEFAULT_PLAYLIST_NAME;
    },
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

/**
 * Tracks fetched and cached per gap (discovery) artist. This is the entire pool a Tier C artist
 * ever draws from until a manual rescan, so a small number means the same handful of songs
 * recycle. Spotify's search endpoint returns up to 50 for the same single API call, so raising
 * this costs storage, not rate limit.
 */
export const GAP_TRACKS_PER_ARTIST = 25;

/**
 * Saves the gap cache, progressively trimming tracks-per-artist if the browser rejects the
 * write. localStorage is ~5 MB and shared with the playlist log, so a deep per-artist cache can
 * tip a large library over. Trimming degrades to fewer deep cuts; silently failing to persist
 * would be far worse, since every build would then rescan from scratch and hit the rate limit.
 */
function saveGapCache(cache) {
    if (save(KEYS.gapCache, cache)) return true;
    for (const cap of [15, 10, 5]) {
        const trimmed = {};
        for (const [id, entry] of Object.entries(cache)) {
            trimmed[id] = { ...entry, tracks: (entry.tracks || []).slice(0, cap) };
        }
        if (save(KEYS.gapCache, trimmed)) {
            console.warn(`[storage] gap cache trimmed to ${cap} tracks/artist to fit quota`);
            return true;
        }
    }
    console.error('[storage] gap cache could not be saved even after trimming');
    return false;
}

export const gapCache = {
    get: () => load(KEYS.gapCache, {}),
    save: (cache) => saveGapCache(cache),
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

/** Playlist snapshots retained. Must be ≥ the largest cooldown setting (song cooldown, 100). */
const MAX_STORED = 100;

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
        // Must cover the largest song-cooldown setting (100); each entry is only ID lists.
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

// ── Artist Library ────────────────────────────────────────────────────────────
// Snapshot of the last build's library, kept so the exports and the cooldown
// recommendation have something to describe without re-running a full scan.
// Structure: { followedArtists: [{id,name}], topArtistIds: string[],
//              lastRefreshedMs: number, lastScan: { scanned, total } | null }

const ARTIST_LIBRARY_DEFAULTS = {
    followedArtists: [],
    topArtistIds: [],
    lastRefreshedMs: 0,
    lastScan: null,
};

export const artistLibrary = {
    get: () => load(KEYS.artistLibrary, ARTIST_LIBRARY_DEFAULTS),
    save: (partial) => save(KEYS.artistLibrary, { ...artistLibrary.get(), ...partial }),
    clear: () => localStorage.removeItem(KEYS.artistLibrary),
};

// ── Playlist Log ──────────────────────────────────────────────────────────────
// Analysis-oriented record of every build: the settings in effect plus every track.
// Distinct from `history` above, which stores ID-only snapshots for cooldown.

/** Builds retained in the log. Matches MAX_LOGGED in the Android PlaylistLogStorage. */
const MAX_LOGGED = 50;

export const playlistLog = {
    get: () => load(KEYS.playlistLog, { entries: [] }),
    clear: () => localStorage.removeItem(KEYS.playlistLog),

    /**
     * Prepends a build entry and trims to MAX_LOGGED (most recent first).
     * The log is the first thing sacrificed under storage pressure — it is analysis data, while
     * the gap cache is what keeps builds cheap — so on a quota failure it drops older entries
     * rather than letting the write fail.
     */
    record(entry) {
        const log = playlistLog.get();
        log.entries = [entry, ...log.entries].slice(0, MAX_LOGGED);
        if (save(KEYS.playlistLog, log)) return true;
        for (const cap of [25, 10, 5, 1]) {
            if (save(KEYS.playlistLog, { entries: log.entries.slice(0, cap) })) {
                console.warn(`[storage] playlist log trimmed to ${cap} builds to fit quota`);
                return true;
            }
        }
        return false;
    },
};

// ── Full clear (logout) ───────────────────────────────────────────────────────

export function clearAll() {
    tokens.clear();
    gapCache.clear();
    history.clear();
    playlistId.clear();
    artistLibrary.clear();
    playlistLog.clear();
    // Keep settings (client ID, preferences)
}
