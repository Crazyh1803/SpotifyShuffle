// api.js — Spotify Web API wrapper
// All calls go directly from the browser to api.spotify.com (CORS supported).

import { tokens, settings } from './storage.js';
import { refreshAccessToken } from './auth.js';

const BASE = 'https://api.spotify.com/v1';

// ── Global rate limiter ───────────────────────────────────────────────────────
// Spotify doesn't publish exact limits, but empirically a large build (200+ calls)
// triggers 429 if calls come in faster than ~2-3 per second.
// Enforcing a minimum 350 ms gap between every call keeps us well under the limit
// and means a 100-call build takes ~35 s — acceptable for a first build.

const CALL_GAP_MS    = 400;
const MAX_BACKOFF_MS = 5 * 60 * 1000;           // cap any Retry-After at 5 minutes
const RL_STORAGE_KEY = 'trueshuffle_rate_limit_until';

// Restore any in-progress rate-limit window from the previous page load.
// Without this, a page refresh resets _nextCallAt to 0 and the app immediately
// hammers Spotify again even though the server-side window is still open.
let _nextCallAt = Math.max(0, parseInt(sessionStorage.getItem(RL_STORAGE_KEY) || '0', 10));

async function throttle() {
    const now  = Date.now();
    const wait = _nextCallAt - now;
    if (wait > 0) await new Promise(r => setTimeout(r, wait));
    _nextCallAt = Date.now() + CALL_GAP_MS;
}

// ── Core fetch wrapper ────────────────────────────────────────────────────────

async function apiFetch(path, options = {}) {
    // Wait until the global rate-limit gap has elapsed before every call
    await throttle();

    let t = tokens.get();

    // Refresh token if expired (with 60s buffer)
    if (t.expiresAt && Date.now() > t.expiresAt - 60_000) {
        const clientId = settings.get().clientId;
        t = await refreshAccessToken(t.refreshToken, clientId);
        tokens.save(t);
    }

    const res = await fetch(`${BASE}${path}`, {
        ...options,
        headers: {
            'Authorization': `Bearer ${t.accessToken}`,
            'Content-Type': 'application/json',
            ...options.headers,
        },
    });

    if (res.status === 429) {
        // Back off for the duration Spotify asks (Retry-After header, or 10 s default).
        // Cap at MAX_BACKOFF_MS (5 min) — extreme values like 67256 s (18+ hours) appear
        // when the API client has been hammering; capping means we retry after 5 min and
        // get another 429 if the window isn't clear yet, rather than freezing the app.
        const retryAfter = parseInt(res.headers.get('Retry-After') || '10', 10);
        const backoffMs  = Math.min(retryAfter * 1000, MAX_BACKOFF_MS);
        _nextCallAt = Date.now() + backoffMs + CALL_GAP_MS;
        // Persist through page refreshes so the user can't reset the window by reloading.
        sessionStorage.setItem(RL_STORAGE_KEY, String(_nextCallAt));
        throw Object.assign(new Error('Rate limited'), { status: 429, retryAfter });
    }
    if (res.status === 401) {
        // Token truly invalid — force re-login
        tokens.clear();
        throw Object.assign(new Error('Session expired. Please log in again.'), { status: 401 });
    }
    if (!res.ok) {
        const body = await res.text().catch(() => '');
        throw Object.assign(new Error(`Spotify ${res.status}: ${body}`), { status: res.status });
    }

    if (res.status === 200 || res.status === 201) {
        return res.json();
    }
    return null; // 204 No Content (e.g. followPlaylist)
}

// ── User ──────────────────────────────────────────────────────────────────────

export async function getUserProfile() {
    return apiFetch('/me');
}

// ── Artists ───────────────────────────────────────────────────────────────────

export async function getAllFollowedArtists() {
    const artists = [];
    let after = null;
    do {
        const url = `/me/following?type=artist&limit=50${after ? `&after=${after}` : ''}`;
        const res = await apiFetch(url);
        artists.push(...res.artists.items);
        after = res.artists.cursors?.after ?? null;
    } while (after);
    return artists;
}

export async function getTopArtists() {
    const results = [];
    for (const range of ['long_term', 'medium_term', 'short_term']) {
        let offset = 0;
        while (offset < 100) {
            const res = await apiFetch(`/me/top/artists?time_range=${range}&limit=50&offset=${offset}`);
            results.push(...res.items);
            if (res.items.length < 50) break;
            offset += 50;
        }
    }
    // Deduplicate by id
    const seen = new Set();
    return results.filter(a => seen.has(a.id) ? false : seen.add(a.id));
}

export async function getTopTracks(timeRange = 'long_term') {
    const tracks = [];
    let offset = 0;
    while (offset < 100) {
        const res = await apiFetch(`/me/top/tracks?time_range=${timeRange}&limit=50&offset=${offset}`);
        tracks.push(...res.items);
        if (res.items.length < 50) break;
        offset += 50;
    }
    return tracks;
}

export async function getArtistTopTracks(artistId, market) {
    // Pass the user's actual country code (from getUserProfile().country).
    // market=from_token was deprecated in Spotify's February 2026 API migration.
    const q = market ? `?market=${encodeURIComponent(market)}` : '';
    return apiFetch(`/artists/${artistId}/top-tracks${q}`);
}

export async function getArtistAlbums(artistId, includeGroups = 'album,single', limit = 20, market) {
    // Pass explicit market — Spotify's Feb 2026 migration deprecated token-based market inference.
    const mq = market ? `&market=${encodeURIComponent(market)}` : '';
    return apiFetch(`/artists/${artistId}/albums?include_groups=${includeGroups}&limit=${limit}${mq}`);
}

export async function getAlbumTracks(albumId, limit = 50, market) {
    // Pass explicit market — avoids 403 for region-locked albums post Feb 2026 migration.
    const mq = market ? `&market=${encodeURIComponent(market)}` : '';
    return apiFetch(`/albums/${albumId}/tracks?limit=${limit}${mq}`);
}

export async function searchTracks(query, limit = 10, market) {
    // market=from_token was deprecated in Spotify's February 2026 API migration.
    const mq = market ? `&market=${encodeURIComponent(market)}` : '';
    return apiFetch(`/search?q=${encodeURIComponent(query)}&type=track&limit=${limit}${mq}`);
}

// ── Library ───────────────────────────────────────────────────────────────────

export async function getAllSavedTracks(onProgress) {
    const tracks = [];
    let offset = 0;
    let total = Infinity;
    while (offset < total) {
        const res = await apiFetch(`/me/tracks?limit=50&offset=${offset}`);
        const batch = res.items.map(i => i.track).filter(Boolean);
        tracks.push(...batch);
        total = res.total;
        offset += 50;
        if (onProgress) onProgress(tracks.length, total);
        if (batch.length < 50) break;
    }
    return tracks;
}

export async function getAllSavedAlbums(onProgress) {
    const albums = [];
    let offset = 0;
    let total = Infinity;
    while (offset < total) {
        const res = await apiFetch(`/me/albums?limit=50&offset=${offset}`);
        albums.push(...res.items.map(i => i.album).filter(Boolean));
        total = res.total;
        offset += 50;
        if (onProgress) onProgress(albums.length, total);
        if (res.items.length < 50) break;
    }
    return albums;
}

// ── Playlists ─────────────────────────────────────────────────────────────────

export async function createPlaylist(userId, name, description) {
    // POST /me/playlists uses the token identity directly and avoids the 403
    // that POST /users/{id}/playlists returns in Spotify Development Mode.
    // Fall back to the user-id path only if /me/playlists returns 404.
    try {
        return await apiFetch('/me/playlists', {
            method: 'POST',
            body: JSON.stringify({ name, description, public: false }),
        });
    } catch (e) {
        if (e.status === 404) {
            return apiFetch(`/users/${userId}/playlists`, {
                method: 'POST',
                body: JSON.stringify({ name, description, public: false }),
            });
        }
        throw e;
    }
}

export async function replacePlaylistTracks(playlistId, uris) {
    // Spotify renamed /tracks → /items in February 2026; /tracks returns 403 in dev mode.
    const res = await apiFetch(`/playlists/${playlistId}/items`, {
        method: 'PUT',
        body: JSON.stringify({ uris: uris.slice(0, 100) }),
    });
    return res;
}

export async function addTracksToPlaylist(playlistId, uris) {
    // Spotify renamed /tracks → /items in February 2026; /tracks returns 403 in dev mode.
    return apiFetch(`/playlists/${playlistId}/items`, {
        method: 'POST',
        body: JSON.stringify({ uris: uris.slice(0, 100) }),
    });
}

export async function followPlaylist(playlistId) {
    try {
        await apiFetch(`/playlists/${playlistId}/followers`, {
            method: 'PUT',
            body: JSON.stringify({ public: false }),
        });
    } catch (e) {
        // Non-fatal — playlist exists, just won't auto-appear in library
        console.warn('followPlaylist failed (non-fatal):', e.message);
    }
}
