// app.js — Main application logic for True Shuffle Web
// Orchestrates auth, API calls, track pool building, shuffle engine, and Spotify save.

import { startAuth, getRedirectUri } from './auth.js?v=26';
import { tokens, settings, gapCache, playlistId, history, artistLibrary, playlistLog,
         clearAll, storageReport, GAP_TRACKS_PER_ARTIST } from './storage.js?v=26';
import * as api from './api.js?v=26';
import { buildPlaylist, maxSustainableCooldown, maxSustainableSongCooldown, tierOf }
    from './engine.js?v=26';

// ── Constants ─────────────────────────────────────────────────────────────────
// Rate limiting is handled globally inside apiFetch (350 ms between every call).
// These constants control how many items we process per build.

const MAX_ALBUM_EXPANSION = 15;   // saved albums to expand per build (keeps Source 3 ≤15 API calls)
const MAX_GAP_BATCH       = 50;   // gap artists scanned per build; search-first = 1 call each ~20s

const delay = ms => new Promise(r => setTimeout(r, ms));

// ── Screen Management ─────────────────────────────────────────────────────────

function showScreen(id) {
    document.querySelectorAll('.screen').forEach(s => s.classList.remove('active'));
    const el = document.getElementById(id);
    if (el) el.classList.add('active');
}

function setStatus(msg) {
    const el = document.getElementById('build-status');
    if (el) el.textContent = msg;
}

function setProgressText(txt) {
    const el = document.getElementById('build-progress');
    if (el) el.textContent = txt;
}

// ── Track helper ──────────────────────────────────────────────────────────────

// Store only the fields the engine and the playlist-log export need — keeps the cache small.
// Album name/date are carried purely for the export; entries cached before they were added
// simply export as blank until the next rescan.
//
// `popularity` no longer influences selection — Spotify stopped returning the field, so it is
// always 0 now. It is still captured because it costs nothing and is our early warning if the
// field ever comes back; see selectTrack() in engine.js for why the bias was removed.
function minifyTrack(t) {
    return {
        id:          t.id,
        name:        t.name,
        duration_ms: t.duration_ms,
        popularity:  t.popularity ?? 0,
        artists:     (t.artists || []).map(a => ({ id: a.id, name: a.name })),
        album:       t.album ? { name: t.album.name, release_date: t.album.release_date } : undefined,
    };
}

// ── Init ──────────────────────────────────────────────────────────────────────

async function init() {
    // ── Recovery: if Spotify redirected to index.html instead of callback.html
    // (happens when the wrong redirect URI was registered in the Spotify dashboard),
    // forward the code/error params to callback.html so the login can complete.
    const urlParams = new URLSearchParams(window.location.search);
    if (urlParams.has('code') || urlParams.has('error')) {
        window.location.replace('callback.html' + window.location.search);
        return;
    }

    // Show computed redirect URI on setup screen
    const uriEl = document.getElementById('redirect-uri-display');
    if (uriEl) uriEl.textContent = getRedirectUri();

    loadSettingsUI();

    if (!settings.get().clientId) {
        showScreen('screen-setup');
    } else {
        showScreen('screen-home');
        updateHomeUI();
    }
}

function updateHomeUI() {
    const loggedIn = tokens.isLoggedIn();
    const loggedOutArea = document.getElementById('home-logged-out');
    const loggedInArea  = document.getElementById('home-logged-in');
    if (loggedOutArea) loggedOutArea.style.display = loggedIn ? 'none' : 'flex';
    if (loggedInArea)  loggedInArea.style.display  = loggedIn ? 'flex' : 'none';
}

// ── Setup Screen ──────────────────────────────────────────────────────────────

document.getElementById('btn-save-client-id')?.addEventListener('click', () => {
    const clientId = document.getElementById('input-client-id')?.value.trim();
    if (!clientId) {
        alert('Please enter your Spotify Client ID.');
        return;
    }
    settings.save({ clientId });
    showScreen('screen-home');
    updateHomeUI();
});

// ── Login / Logout ────────────────────────────────────────────────────────────

document.getElementById('btn-login')?.addEventListener('click', async () => {
    const s = settings.get();
    if (!s.clientId) { showScreen('screen-setup'); return; }
    try {
        await startAuth(s.clientId);   // redirects to Spotify — page unloads
    } catch (e) {
        showError(e.message);
    }
});

document.getElementById('btn-logout')?.addEventListener('click', () => {
    clearAll();
    updateHomeUI();
});

// ── Build Playlist ────────────────────────────────────────────────────────────

document.getElementById('btn-build')?.addEventListener('click', () => buildFlow());
document.getElementById('btn-rebuild')?.addEventListener('click', () => buildFlow());

// Force a fresh login and show a message — used when the stored token lacks scopes.
function forceRelogin(reason) {
    tokens.clear();
    updateHomeUI();
    showScreen('screen-home');
    // Briefly surface an explanation on the home screen
    const notice = document.getElementById('relogin-notice');
    if (notice) {
        notice.textContent = reason;
        notice.style.display = 'block';
        setTimeout(() => { notice.style.display = 'none'; }, 12000);
    }
}

async function buildFlow() {
    showScreen('screen-building');
    setProgressText('');

    try {
        const s = settings.get();

        // ── Pre-flight: verify the token has playlist scopes ──────────────────
        // Tokens granted before playlist scopes were added won't be able to save.
        // Catch it here instead of after a 60-second scan.
        const tokenData = tokens.get();
        const grantedScopes = (tokenData.scope || '').split(' ');
        const needsPlaylist = ['playlist-modify-private', 'playlist-modify-public'];
        const missingScope = needsPlaylist.some(sc => !grantedScopes.includes(sc));
        if (missingScope) {
            forceRelogin('Your Spotify login needs to be refreshed to allow playlist creation. Please log in again.');
            return;
        }

        // ── Step 1: Fetch library ─────────────────────────────────────────────
        setStatus('Fetching your profile…');
        const user = await api.getUserProfile();

        setStatus('Fetching followed artists…');
        const followedArtists = await api.getAllFollowedArtists();

        setStatus('Fetching your top artists…');
        const topArtists = await api.getTopArtists();
        const topIds = new Set(topArtists.map(a => a.id));

        setStatus('Fetching your top tracks…');
        const topTracks = await api.getTopTracks('long_term');

        setStatus('Fetching liked songs…');
        const likedTracks = await api.getAllSavedTracks((loaded, total) => {
            setStatus(`Fetching liked songs… ${loaded} / ${total}`);
        });

        setStatus('Fetching saved albums…');
        const savedAlbums = await api.getAllSavedAlbums((loaded, total) => {
            setStatus(`Fetching saved albums… ${loaded} / ${total}`);
        });

        // ── Step 2: Build initial track map (Sources 1–3) ─────────────────────
        const tracksByArtist = {};  // artistId → Track[]
        const likedIds = new Set();

        // Tracks which artists appear as the PRIMARY (first) artist on any Source 1-3 track.
        // Used to determine gap artists: a followed artist is a gap artist only if they have
        // no PRIMARY coverage from Sources 1-3. Artists appearing solely as features on
        // other artists' tracks should still be treated as gap artists and scanned for Tier C.
        // (tracksByArtist still gets all-artist entries so the track pool stays rich.)
        const primaryCoveredIds = new Set();

        function addTrack(track) {
            if (!track?.id || !track.artists?.length) return;
            primaryCoveredIds.add(track.artists[0].id);  // only primary artist counts as "covered"
            for (const artist of track.artists) {
                if (!tracksByArtist[artist.id]) tracksByArtist[artist.id] = [];
                if (!tracksByArtist[artist.id].some(t => t.id === track.id)) {
                    tracksByArtist[artist.id].push(track);
                }
            }
        }

        // Source 1: top tracks
        topTracks.forEach(addTrack);

        // Source 2: liked songs
        for (const t of likedTracks) {
            likedIds.add(t.id);
            addTrack(t);
        }

        // Source 3: saved album tracks (capped — keeps this source to ≤MAX_ALBUM_EXPANSION calls)
        const albumsToExpand = savedAlbums.slice(0, MAX_ALBUM_EXPANSION);
        if (albumsToExpand.length > 0) {
            setStatus(`Expanding ${albumsToExpand.length} saved album${albumsToExpand.length === 1 ? '' : 's'}…`);
            let albumRateLimited = false;
            for (let i = 0; i < albumsToExpand.length; i++) {
                if (albumRateLimited) break;  // stop hammering on first 429
                const album = albumsToExpand[i];
                if (i % 5 === 0) setProgressText(`${i + 1} / ${albumsToExpand.length}`);
                try {
                    // Pass user.country — Spotify Feb 2026 deprecated token-inferred market.
                    const res = await api.getAlbumTracks(album.id, 50, user.country);
                    for (const t of res.items) {
                        addTrack({ ...t, album: { id: album.id, name: album.name, images: album.images } });
                    }
                } catch (e) {
                    if (e.status === 429) albumRateLimited = true;
                    // other errors: skip silently
                }
            }
            setProgressText('');
        }

        // ── Step 3: Decide mode ───────────────────────────────────────────────
        const likedSongsOnlyMode = followedArtists.length === 0;
        let effectiveFollowedArtists;
        let effectiveTracksByArtist;
        const discoveryIds = new Set();

        if (!likedSongsOnlyMode) {
            // Normal mode: gap-fill followed artists missing from Sources 1–3
            effectiveFollowedArtists = followedArtists;
            const followedIds = new Set(followedArtists.map(a => a.id));

            // Gap artists = followed artists with no PRIMARY track coverage from Sources 1-3.
            // Using primaryCoveredIds (not tracksByArtist) means artists who only appear as
            // features on other artists' tracks are still treated as undiscovered and scanned.
            const gapArtists = followedArtists.filter(a => !primaryCoveredIds.has(a.id));

            if (gapArtists.length > 0) {
                const cache    = gapCache.get();

                // Load cached tracks first (even stale ones — better than nothing)
                for (const [artistId, entry] of Object.entries(cache)) {
                    if (!followedIds.has(artistId)) continue;
                    for (const t of entry.tracks) addTrack(t);
                    if (!topIds.has(artistId) && entry.tracks.length > 0) {
                        discoveryIds.add(artistId);
                    }
                }

                // Decide who still needs scanning
                const unscanned = gapArtists.filter(a => !cache[a.id]);
                const stale     = gapArtists.filter(a => cache[a.id]?.scannedAtMs === 0);
                const toScan    = [...unscanned, ...stale].slice(0, MAX_GAP_BATCH);
                console.log(`[gap] total=${gapArtists.length} unscanned=${unscanned.length} stale=${stale.length} toScan=${toScan.length}`);

                if (toScan.length > 0) {
                    setStatus(`Scanning ${toScan.length} artist${toScan.length === 1 ? '' : 's'} for deep cuts…`);
                    const newCache = { ...cache };
                    let scanned = 0;
                    let rateLimited = false;
                    let firstScanError = null;   // diagnostic: surface the first failure

                    let topTracksBlocked = false;

                    for (const artist of toScan) {
                        if (rateLimited) break;
                        setProgressText(`${scanned + 1} / ${toScan.length}`);

                        const found = [];

                        // ── Strategy 1: search (1 API call — fastest, works in all modes) ──
                        // Searching by artist name + filtering to exact ID is the cheapest
                        // path and works in Spotify dev mode. Most gap artists get covered
                        // here with a single call.
                        if (!rateLimited) {
                            try {
                                const searchRes = await api.searchTracks(
                                    `artist:"${artist.name}"`, GAP_TRACKS_PER_ARTIST, user.country);
                                const filtered = (searchRes.tracks?.items || [])
                                    .filter(t => (t.artists || []).some(a => a.id === artist.id))
                                    .map(t => minifyTrack(t));
                                found.push(...filtered);
                                if (filtered.length > 0) firstScanError = null;
                            } catch (e) {
                                if (!firstScanError) firstScanError = e;
                                if (e.status === 429) rateLimited = true;
                            }
                        }

                        // ── Strategy 2: top-tracks fallback (1 call, may 403 in dev mode) ─
                        if (found.length === 0 && !topTracksBlocked && !rateLimited) {
                            try {
                                const res = await api.getArtistTopTracks(artist.id, user.country);
                                found.push(...(res.tracks || []).map(minifyTrack));
                                if (found.length > 0) firstScanError = null;
                            } catch (e) {
                                if (!firstScanError) firstScanError = e;
                                if (e.status === 403) topTracksBlocked = true;
                                if (e.status === 429) rateLimited = true;
                            }
                        }

                        // ── Strategy 3: album-based last resort (3+ calls) ────────────────
                        // More expensive but finds deep cuts when search & top-tracks fail.
                        // Picks 2 random albums/singles; keeps only tracks credited to this artist.
                        if (found.length === 0 && !rateLimited) {
                            try {
                                const albumsRes = await api.getArtistAlbums(artist.id, 'album,single', 10, user.country);
                                const albums = (albumsRes.items || []).sort(() => Math.random() - 0.5).slice(0, 2);
                                for (const album of albums) {
                                    if (rateLimited) break;
                                    try {
                                        const tracksRes = await api.getAlbumTracks(album.id, 50, user.country);
                                        const albumTracks = (tracksRes.items || [])
                                            .filter(t => t.id && t.uri?.startsWith('spotify:track:'))
                                            .filter(t => (t.artists || []).some(a => a.id === artist.id))
                                            .map(t => minifyTrack({ ...t, popularity: 0 }));
                                        found.push(...albumTracks);
                                    } catch (e) {
                                        if (e.status === 429) { rateLimited = true; break; }
                                    }
                                }
                                if (found.length > 0) firstScanError = null;
                            } catch (e) {
                                if (!firstScanError) firstScanError = e;
                                if (e.status === 429) rateLimited = true;
                            }
                        }

                        if (found.length > 0) firstScanError = null;

                        const dedupedTracks = found.filter((t, i, a) => a.findIndex(x => x.id === t.id) === i);
                        for (const t of dedupedTracks) addTrack(t);
                        // If we were rate-limited and got nothing, mark scannedAtMs=0 so this
                        // artist is retried on the next build rather than permanently cached empty.
                        const scannedMs = (rateLimited && dedupedTracks.length === 0) ? 0 : Date.now();
                        newCache[artist.id] = { tracks: dedupedTracks, scannedAtMs: scannedMs };
                        if (!topIds.has(artist.id) && dedupedTracks.length > 0) {
                            discoveryIds.add(artist.id);
                        }

                        scanned++;
                        await delay(100);
                    }

                    // If every scan attempt returned zero tracks, surface the error.
                    // Check actual track counts — cache keys are always added even on failure.
                    const anySucceeded = toScan.some(a => (newCache[a.id]?.tracks?.length ?? 0) > 0);
                    if (!anySucceeded && firstScanError) {
                        console.error('Gap scan: all attempts failed. First error:', firstScanError);
                        window.__scanError = firstScanError.message;
                    }

                    gapCache.save(newCache);
                    setProgressText('');

                    // Report scan progress (inside the scan block — newCache has updated entries)
                    const totalGap    = gapArtists.length;
                    const totalCached = Object.keys(newCache).filter(id => followedIds.has(id)).length;
                    window.__scanProgress = { scanned: totalCached, total: totalGap };
                } else {
                    // Nothing new to scan this build — report progress from existing cache
                    const currentCache = gapCache.get();
                    const totalGap    = gapArtists.length;
                    const totalCached = Object.keys(currentCache).filter(id => followedIds.has(id)).length;
                    window.__scanProgress = { scanned: totalCached, total: totalGap };
                }
            }

            effectiveTracksByArtist = tracksByArtist;

        } else {
            // Liked-songs-only mode — synthesize artists from liked tracks
            const artistMap = {};
            for (const track of likedTracks) {
                for (const a of track.artists) {
                    if (!artistMap[a.id]) artistMap[a.id] = { id: a.id, name: a.name };
                }
            }
            effectiveFollowedArtists = Object.values(artistMap);

            if (s.likedSongsExploreMode) {
                // Explore: full pool (top tracks + liked + saved albums)
                effectiveTracksByArtist = tracksByArtist;
            } else {
                // Strict (default): only the exact songs the user has liked
                effectiveTracksByArtist = {};
                for (const track of likedTracks) {
                    for (const a of track.artists) {
                        if (!effectiveTracksByArtist[a.id]) effectiveTracksByArtist[a.id] = [];
                        if (!effectiveTracksByArtist[a.id].some(t => t.id === track.id)) {
                            effectiveTracksByArtist[a.id].push(track);
                        }
                    }
                }
            }
        }

        if (effectiveFollowedArtists.length === 0) {
            showError('No tracks found in your Spotify library.\n\nLike some songs or save some albums in Spotify, then try again.');
            return;
        }

        // ── Step 4: Shuffle ───────────────────────────────────────────────────
        setStatus('Building your playlist…');
        // Song and artist cooldown are independent settings backed by the same history.
        const songCooldownN   = s.cooldownPlaylists       ?? 5;
        const artistCooldownN = s.artistCooldownPlaylists ?? 5;
        const cooldownTrackIds  = history.getCooldownTrackIds(songCooldownN);
        const recentArtistSets  = history.getRecentArtistSets(artistCooldownN);

        const result = buildPlaylist({
            followedArtists:   effectiveFollowedArtists,
            topIds,
            tracksByArtist:    effectiveTracksByArtist,
            discoveryIds,
            likedIds,
            cooldownTrackIds,
            recentArtistSets,
            discoveryBias:     s.discoveryBias     ?? 60,
            targetDurationMs:  s.playlistDurationMs ?? 2 * 60 * 60 * 1000,
        });

        // Remember the pool size so Settings can show the cooldown recommendation without
        // re-scanning the library. Counts artists that could actually contribute a track.
        const poolArtists = effectiveFollowedArtists.filter(
            a => (effectiveTracksByArtist[a.id] || []).length > 0);
        // Distinct tracks across the whole pool — drives the song-cooldown recommendation,
        // which is now a hard no-repeat guarantee rather than a preference.
        const distinctTrackIds = new Set();
        for (const a of poolArtists) {
            for (const t of effectiveTracksByArtist[a.id]) distinctTrackIds.add(t.id);
        }
        settings.save({
            lastArtistPoolSize: poolArtists.length,
            lastTrackPoolSize:  distinctTrackIds.size,
        });

        if (result.tracks.length === 0) {
            showError('Could not build a playlist — your library may be too small. Try Again after adding more songs.');
            return;
        }

        // ── Step 5: Save to Spotify ───────────────────────────────────────────
        setStatus('Saving playlist to Spotify…');
        const uris = result.tracks.map(t => `spotify:track:${t.id}`);
        const desc = `Built by True Shuffle • ${new Date().toLocaleDateString()}`;
        const playlistName = settings.resolvedPlaylistName();
        let pid = playlistId.get();
        let playlistUrl;

        // Helper: push all URIs to a playlist in 100-track chunks
        async function pushTracks(id, trackUris) {
            await api.replacePlaylistTracks(id, trackUris.slice(0, 100));
            for (let i = 100; i < trackUris.length; i += 100) {
                await api.addTracksToPlaylist(id, trackUris.slice(i, i + 100));
            }
        }

        // Try updating the stored playlist first
        if (pid) {
            try {
                await pushTracks(pid, uris);
                // Apply a rename to the existing playlist. Skipped unless the name actually
                // changed, so the normal rebuild path costs no extra API call.
                if (playlistName !== s.appliedPlaylistName) {
                    try {
                        await api.changePlaylistDetails(pid, playlistName, desc);
                    } catch (e) {
                        if (e.status === 401) throw e;
                        // Non-fatal: the tracks are already saved, only the name is stale.
                        console.warn('Playlist rename failed (non-fatal):', e.message);
                    }
                }
                await api.followPlaylist(pid);
                playlistUrl = `https://open.spotify.com/playlist/${pid}`;
            } catch (e) {
                if (e.status === 401) throw e;   // session expired — re-throw
                // Playlist gone / inaccessible — clear it and create a fresh one
                playlistId.clear();
                pid = null;
            }
        }

        // Create a brand-new playlist if needed
        if (!pid) {
            let step = 'createPlaylist';
            try {
                const pl = await api.createPlaylist(user.id, playlistName, desc);
                playlistId.save(pl.id);
                pid = pl.id;
                step = 'replacePlaylistTracks';
                await pushTracks(pid, uris);
                step = 'followPlaylist';
                await api.followPlaylist(pid);
                playlistUrl = `https://open.spotify.com/playlist/${pid}`;
            } catch (e) {
                if (e.status === 401) throw e;
                if (e.status === 403) {
                    showError(`403 at step "${step}": ${e.message}`);
                    return;
                }
                throw e;
            }
        }

        // Remember the name Spotify now holds, so later builds skip the rename call.
        settings.save({ appliedPlaylistName: playlistName });

        // Snapshot the library so the exports have something to describe between builds.
        artistLibrary.save({
            followedArtists: followedArtists.map(a => ({ id: a.id, name: a.name })),
            topArtistIds:    [...topIds],
            lastRefreshedMs: Date.now(),
            lastScan:        window.__scanProgress ?? null,
        });

        // Record the full playlist to the analysis log (separate from cooldown history).
        playlistLog.record(buildPlaylistLogEntry({
            tracks: result.tracks,
            discoveryIds, topIds, likedIds,
            discoveryBias:    s.discoveryBias ?? 60,
            targetDurationMs: s.playlistDurationMs ?? 2 * 60 * 60 * 1000,
            songCooldownN, artistCooldownN,
        }));

        // Record to cooldown history
        history.record(result.tracks);

        // ── Step 6: Show success ──────────────────────────────────────────────
        // A hard song cooldown can legitimately leave the build short: every remaining track is
        // inside the user's no-repeat window. Say so rather than quietly returning a short list.
        const shortEl = document.getElementById('short-notice');
        if (shortEl) {
            if (result.shortOfTarget) {
                const mins = Math.round(result.durationMs / 60000);
                shortEl.textContent =
                    `Stopped at ${mins} min: every remaining song is still inside your ` +
                    `${songCooldownN}-playlist cooldown. Lower it, or build again once the ` +
                    `artist scan finishes.`;
                shortEl.style.display = 'block';
            } else {
                shortEl.style.display = 'none';
            }
        }

        showSuccess({
            trackCount:        result.tracks.length,
            durationMs:        result.tracks.reduce((s, t) => s + (t.duration_ms || 0), 0),
            tierACount:        result.tierACount,
            tierBCount:        result.tierBCount,
            tierCCount:        result.tierCCount,
            playlistUrl,
            likedSongsOnlyMode,
        });

    } catch (e) {
        if (e.status === 401) {
            clearAll();
            updateHomeUI();
            showError('Your session has expired. Please log in again.');
        } else {
            showError(e.message || 'Something went wrong. Please try again.');
        }
    }
}

// ── Success / Error screens ───────────────────────────────────────────────────

function showSuccess({ trackCount, durationMs, tierACount, tierBCount, tierCCount, playlistUrl, likedSongsOnlyMode }) {
    showScreen('screen-success');

    const mins = Math.round(durationMs / 60_000);
    const hrs  = (mins / 60).toFixed(1);

    const countEl = document.getElementById('success-track-count');
    if (countEl) countEl.textContent = `${trackCount} tracks · ${hrs} hr`;

    const tierEl = document.getElementById('success-tiers');
    if (tierEl) {
        tierEl.textContent = likedSongsOnlyMode
            ? 'Shuffled from your liked songs'
            : `${tierACount} favourite · ${tierBCount} familiar · ${tierCCount} discovery`;
    }

    const linkEl = document.getElementById('success-link');
    if (linkEl) {
        linkEl.href = playlistUrl;
    }

    // Show scan progress if library is not yet fully scanned
    const progressEl = document.getElementById('scan-progress');
    if (progressEl) {
        const sp = window.__scanProgress;
        if (window.__scanError) {
            progressEl.textContent = `Scan error: ${window.__scanError}`;
            progressEl.style.display = 'block';
        } else if (sp && sp.scanned < sp.total && !likedSongsOnlyMode) {
            progressEl.textContent = `Library: ${sp.scanned} / ${sp.total} artists scanned · Build again for more`;
            progressEl.style.display = 'block';
        } else {
            progressEl.style.display = 'none';
        }
    }
}

function showError(msg) {
    showScreen('screen-error');
    const el = document.getElementById('error-message');
    if (el) el.textContent = msg;
}

document.getElementById('btn-try-again')?.addEventListener('click', () => {
    showScreen('screen-home');
    updateHomeUI();
});

// ── Settings Panel ────────────────────────────────────────────────────────────

/**
 * Settings widgets whose text depends on state that changes OUTSIDE the panel — a build
 * updates the artist pool size and the applied playlist name. loadSettingsUI() only runs
 * once at page load, so without re-running these on open the panel shows figures frozen at
 * whatever they were when the page loaded.
 */
const settingsRefreshers = [];
function refreshSettingsPanel() {
    for (const fn of settingsRefreshers) fn();
}

document.getElementById('btn-settings')?.addEventListener('click', () => {
    refreshSettingsPanel();
    document.getElementById('settings-overlay')?.classList.add('open');
});

document.getElementById('btn-settings-close')?.addEventListener('click', () => {
    document.getElementById('settings-overlay')?.classList.remove('open');
});

// Close settings when tapping the backdrop
document.getElementById('settings-overlay')?.addEventListener('click', (e) => {
    if (e.target === e.currentTarget) {
        e.currentTarget.classList.remove('open');
    }
});

function loadSettingsUI() {
    const s = settings.get();
    settingsRefreshers.length = 0;   // idempotent if this is ever called more than once

    // Discovery bias slider
    const biasEl    = document.getElementById('input-bias');
    const biasValEl = document.getElementById('bias-value');
    if (biasEl) {
        biasEl.value = s.discoveryBias;
        if (biasValEl) biasValEl.textContent = `${s.discoveryBias}%`;
        biasEl.addEventListener('input', () => {
            const v = parseInt(biasEl.value);
            if (biasValEl) biasValEl.textContent = `${v}%`;
            settings.save({ discoveryBias: v });
        });
    }

    // Playlist name
    const nameEl     = document.getElementById('input-playlist-name');
    const nameHintEl = document.getElementById('playlist-name-hint');
    if (nameEl) {
        nameEl.value = s.playlistName ?? '';
        const refreshNameHint = () => {
            if (!nameHintEl) return;
            const applied = settings.get().appliedPlaylistName;
            const resolved = settings.resolvedPlaylistName();
            // Only promise a rename when there's an existing playlist carrying a different name.
            nameHintEl.textContent = (applied && applied !== resolved)
                ? `Your next build will rename the playlist to "${resolved}".`
                : `Saved to Spotify as "${resolved}".`;
        };
        nameEl.addEventListener('input', () => {
            settings.save({ playlistName: nameEl.value });
            refreshNameHint();
        });
        settingsRefreshers.push(refreshNameHint);
        refreshNameHint();
    }

    // Playlist duration slider (hours)
    const durEl    = document.getElementById('input-duration');
    const durValEl = document.getElementById('duration-value');
    if (durEl) {
        const hrs = Math.round((s.playlistDurationMs / 3_600_000) * 2) / 2;  // nearest 0.5
        durEl.value = hrs;
        if (durValEl) durValEl.textContent = `${hrs.toFixed(1)} hr`;
        durEl.addEventListener('input', () => {
            const v = parseFloat(durEl.value);
            if (durValEl) durValEl.textContent = `${v.toFixed(1)} hr`;
            settings.save({ playlistDurationMs: Math.round(v * 3_600_000) });
        });
    }

    // ── Cooldown sliders ─────────────────────────────────────────────────────
    // Song and artist cooldown are independent. The artist recommendation is exact — it mirrors
    // the engine's own starvation-floor math — so it also depends on the target duration and is
    // refreshed whenever the duration slider moves.
    const artistRecEl = document.getElementById('artist-cooldown-rec');

    const songRecEl = document.getElementById('song-cooldown-rec');

    function refreshSongRecommendation() {
        if (!songRecEl) return;
        const cur = settings.get();
        const pool = cur.lastTrackPoolSize ?? 0;
        if (pool <= 0) {
            songRecEl.textContent = 'Build a playlist once and a recommendation will appear here.';
            return;
        }
        const rec = Math.min(100, Math.max(1,
            maxSustainableSongCooldown(pool, cur.playlistDurationMs ?? 7200000)));
        const chosen = cur.cooldownPlaylists ?? 5;
        // Under the ceiling this is headroom, not a warning — lead with what the library
        // supports so a setting well inside budget doesn't read as an overreach.
        songRecEl.textContent = chosen > rec
            ? `Your ${pool} tracks cover about ${rec} builds. A song never repeats inside your ` +
              `window, so at ${chosen} the last builds start coming up short.`
            : `Your ${pool} tracks support up to ${rec} builds with no song repeating — ` +
              `you're at ${chosen}.`;
    }

    function refreshArtistRecommendation() {
        if (!artistRecEl) return;
        const cur = settings.get();
        const pool = cur.lastArtistPoolSize ?? 0;
        if (pool <= 0) {
            // No build yet — nothing to base a recommendation on.
            artistRecEl.textContent = 'Build a playlist once and a recommendation will appear here.';
            return;
        }
        const rec = Math.min(30, Math.max(1,
            maxSustainableCooldown(pool, cur.playlistDurationMs ?? 2 * 60 * 60 * 1000)));
        const hrs = ((cur.playlistDurationMs ?? 7_200_000) / 3_600_000).toFixed(1);
        const chosen = cur.artistCooldownPlaylists ?? 5;
        artistRecEl.textContent = chosen > rec
            ? `Recommended: ${rec} — what ${pool} artists sustain for a ${hrs} hr playlist. ` +
              `Past that the pool would starve, so ${chosen} behaves like ${rec}.`
            : `Recommended: ${rec} — what ${pool} artists sustain for a ${hrs} hr playlist.`;
    }

    function wireCooldownSlider(inputId, valueId, settingKey, fallback, onChange) {
        const el    = document.getElementById(inputId);
        const valEl = document.getElementById(valueId);
        if (!el) return;
        const initial = s[settingKey] ?? fallback;
        el.value = initial;
        if (valEl) valEl.textContent = `${initial}`;
        el.addEventListener('input', () => {
            const v = parseInt(el.value, 10);
            if (valEl) valEl.textContent = `${v}`;
            settings.save({ [settingKey]: v });
            onChange?.();
        });
    }

    wireCooldownSlider('input-song-cooldown', 'song-cooldown-value', 'cooldownPlaylists', 5,
        refreshSongRecommendation);
    settingsRefreshers.push(refreshSongRecommendation);
    refreshSongRecommendation();
    wireCooldownSlider('input-artist-cooldown', 'artist-cooldown-value',
        'artistCooldownPlaylists', 5, refreshArtistRecommendation);
    settingsRefreshers.push(refreshArtistRecommendation);
    refreshArtistRecommendation();

    // A longer playlist consumes more artists per build, so it sustains a shorter cooldown.
    durEl?.addEventListener('input', refreshArtistRecommendation);
    durEl?.addEventListener('input', refreshSongRecommendation);

    // Liked songs explore toggle
    const exploreEl = document.getElementById('input-explore');
    if (exploreEl) {
        exploreEl.checked = s.likedSongsExploreMode;
        exploreEl.addEventListener('change', () => {
            settings.save({ likedSongsExploreMode: exploreEl.checked });
        });
    }

    // Scan for new tracks (clears cache timestamps → next build rescans)
    const clearBtn = document.getElementById('btn-clear-cache');
    if (clearBtn) {
        clearBtn.addEventListener('click', () => {
            gapCache.clearTimestamps();
            clearBtn.textContent = 'Cache cleared ✓';
            setTimeout(() => { clearBtn.textContent = 'Scan for new tracks'; }, 2000);
        });
    }

    // Exports
    wireExportButton('btn-export-artists',  exportArtistList,  'No artists yet');
    wireExportButton('btn-export-diag',     exportDiagnostics, 'Export failed');
    wireExportButton('btn-export-playlists', exportPlaylistLog, 'No builds logged yet');

    const clearLogBtn = document.getElementById('btn-clear-playlist-log');
    if (clearLogBtn) {
        clearLogBtn.addEventListener('click', () => {
            playlistLog.clear();
            clearLogBtn.textContent = 'Log cleared ✓';
            setTimeout(() => { clearLogBtn.textContent = 'Clear playlist log'; }, 2000);
        });
    }

    // Change client ID link
    document.getElementById('btn-change-client-id')?.addEventListener('click', () => {
        document.getElementById('settings-overlay')?.classList.remove('open');
        document.getElementById('input-client-id').value = settings.get().clientId;
        showScreen('screen-setup');
    });
}

// ── Exports ───────────────────────────────────────────────────────────────────
// Browser equivalents of the Android app's three exports. Everything is built from
// localStorage and downloaded client-side — nothing is uploaded anywhere, and none of
// these files contain the Client ID or any OAuth token.

/** Triggers a client-side file download. */
function downloadFile(fileName, mimeType, content) {
    const url = URL.createObjectURL(new Blob([content], { type: `${mimeType};charset=utf-8` }));
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    // Revoke on the next tick — revoking synchronously can cancel the download in some browsers.
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** Escapes a CSV cell: quote-wrap and double inner quotes when it contains , " or a newline. */
function csvCell(value) {
    const s = String(value ?? '');
    return /[",\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
}

const pad = (n) => String(n).padStart(2, '0');
function stamp(withTime) {
    const d = new Date();
    const date = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`;
    return withTime ? `${date}_${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}` : date;
}

/**
 * Builds one playlist-log entry from a finished build. Mirrors buildPlaylistLogEntry in the
 * Android app so both platforms' exports have identical columns and tier classification.
 */
function buildPlaylistLogEntry({
    tracks, discoveryIds, topIds, likedIds,
    discoveryBias, targetDurationMs, songCooldownN, artistCooldownN,
}) {
    const now = Date.now();
    const trackLogs = tracks.map(t => {
        const primary = (t.artists || [])[0];
        return {
            trackId:     t.id,
            trackName:   t.name ?? '',
            artistId:    primary?.id ?? '',
            artistName:  primary?.name ?? '',
            albumName:   t.album?.name ?? '',
            releaseDate: t.album?.release_date ?? '',
            popularity:  t.popularity ?? 0,
            durationMs:  t.duration_ms ?? 0,
            tier:        tierOf(t, discoveryIds, topIds),
            liked:       likedIds.has(t.id),
        };
    });
    return {
        timestampMs: now,
        timestampIso: new Date(now).toISOString(),
        source: 'manual',                 // the web app has no background rebuild
        discoveryBias,
        targetDurationMs,
        songCooldownPlaylists: songCooldownN,
        artistCooldownPlaylists: artistCooldownN,
        trackCount: trackLogs.length,
        artistCount: new Set(tracks.flatMap(t => (t.artists || []).map(a => a.id))).size,
        tierACount: trackLogs.filter(t => t.tier === 'A').length,
        tierBCount: trackLogs.filter(t => t.tier === 'B').length,
        tierCCount: trackLogs.filter(t => t.tier === 'C').length,
        tracks: trackLogs,
    };
}

/** Followed artist names, one per line. Returns the file name, or null when empty. */
function exportArtistList() {
    const names = [...new Set(artistLibrary.get().followedArtists.map(a => a.name))].sort();
    if (names.length === 0) return null;
    const fileName = `shuffle_all_artists_${stamp(false)}.csv`;
    downloadFile(fileName, 'text/csv', names.join('\n'));
    return fileName;
}

/** Library/cache/settings snapshot for bug reports. Never includes the Client ID or tokens. */
function exportDiagnostics() {
    const lib      = artistLibrary.get();
    const cache    = gapCache.get();
    const s        = settings.get();
    const entries  = Object.values(cache);
    const now      = Date.now();

    const scanned   = entries.filter(e => e.scannedAtMs > 0).length;
    const empty     = entries.filter(e => e.scannedAtMs > 0 && (e.tracks || []).length === 0).length;
    const unscanned = entries.filter(e => e.scannedAtMs === 0).length;
    const neverAttempted = Math.max(0, lib.followedArtists.length - entries.length);
    const progress  = window.__scanProgress ?? lib.lastScan;

    const lines = [
        '=== True Shuffle Diagnostics (Web) ===',
        `Generated : ${new Date().toISOString()}`,
        '',
        '--- Library ---',
        `Followed artists : ${lib.followedArtists.length}`,
        `Top artists      : ${lib.topArtistIds.length}`,
        // The pool the engine actually draws from (artists with ≥1 usable track), which is
        // what drives the cooldown recommendation — not the raw follow count above it.
        `Artist pool      : ${s.lastArtistPoolSize || 0}  (had tracks on the last build)`,
        `Track pool       : ${s.lastTrackPoolSize || 0}  (distinct tracks available)`,
        `Recommended artist cooldown : ${s.lastArtistPoolSize
            ? Math.min(30, Math.max(1, maxSustainableCooldown(s.lastArtistPoolSize, s.playlistDurationMs ?? 7200000)))
            : 'n/a'}`,
        `Last refreshed   : ${lib.lastRefreshedMs ? new Date(lib.lastRefreshedMs).toISOString() : 'never'}`,
        '',
        '--- Gap Artist Cache ---',
        `Total entries    : ${entries.length}`,
        `Never attempted  : ${neverAttempted}  (no cache entry yet)`,
        `Scanned          : ${scanned}`,
        `  of which empty : ${empty}  (scanned but no accessible tracks)`,
        `Unscanned (retry): ${unscanned}  (attempted but result was 0 timestamp)`,
        '',
        '--- Scan Progress ---',
        progress
            ? `Scanned / Total  : ${progress.scanned} / ${progress.total}\nComplete         : ${progress.scanned >= progress.total}`
            : 'No build completed yet',
        '',
        '--- Settings ---',
        `Discovery bias         : ${s.discoveryBias}%`,
        `Playlist duration      : ${Math.round((s.playlistDurationMs ?? 0) / 60000)} min`,
        `Song cooldown          : ${s.cooldownPlaylists} playlists`,
        `Artist cooldown        : ${s.artistCooldownPlaylists} playlists`,
        `Playlist name          : ${settings.resolvedPlaylistName()}`,
        `Liked-songs explore    : ${s.likedSongsExploreMode ? 'On' : 'Off'}`,
        '',
        '--- Browser ---',
        `User agent       : ${navigator.userAgent}`,
        `Language         : ${navigator.language}`,
        `Playlist log     : ${playlistLog.get().entries.length} builds recorded`,
        `Local storage    : ${(storageReport().bytes / 1024).toFixed(0)} KB used`,
        `Storage errors   : ${storageReport().lastQuotaError
            ? `${storageReport().lastQuotaError.key} — ${storageReport().lastQuotaError.message}`
            : 'none'}`,
    ];

    const fileName = `trueshuffle_diag_${stamp(true)}.txt`;
    downloadFile(fileName, 'text/plain', lines.join('\n'));
    return fileName;
}

/**
 * Playlist log as a flat CSV (one row per track, build fields repeated) plus the lossless
 * JSON. Same columns as the Android export so one analysis script reads both.
 */
function exportPlaylistLog() {
    const log = playlistLog.get();
    if (log.entries.length === 0) return null;

    const header = [
        'build_timestamp', 'source', 'discovery_bias', 'target_duration_min',
        'song_cooldown_playlists', 'artist_cooldown_playlists',
        'build_track_count', 'build_artist_count',
        'build_tier_a', 'build_tier_b', 'build_tier_c',
        'track_name', 'artist_name', 'album_name', 'release_date',
        'popularity', 'duration_ms', 'tier', 'liked', 'track_id', 'artist_id',
    ];
    const rows = [header.join(',')];
    for (const e of log.entries) {
        for (const t of e.tracks) {
            rows.push([
                e.timestampIso, e.source, e.discoveryBias,
                Math.round(e.targetDurationMs / 60000),
                e.songCooldownPlaylists, e.artistCooldownPlaylists,
                e.trackCount, e.artistCount,
                e.tierACount, e.tierBCount, e.tierCCount,
                csvCell(t.trackName), csvCell(t.artistName), csvCell(t.albumName),
                csvCell(t.releaseDate), t.popularity, t.durationMs,
                t.tier, t.liked, t.trackId, t.artistId,
            ].join(','));
        }
    }

    const ts = stamp(true);
    const csvName = `trueshuffle_playlists_${ts}.csv`;
    downloadFile(csvName, 'text/csv', rows.join('\n'));
    downloadFile(`trueshuffle_playlists_${ts}.json`, 'application/json', JSON.stringify(log, null, 2));
    return csvName;
}

/** Wires an export button, showing the result inline on the button itself. */
function wireExportButton(id, exportFn, emptyMessage) {
    const btn = document.getElementById(id);
    if (!btn) return;
    const label = btn.textContent;
    btn.addEventListener('click', () => {
        let ok = false;
        try {
            ok = !!exportFn();
        } catch (e) {
            console.error('Export failed:', e);
        }
        btn.textContent = ok ? 'Exported ✓' : emptyMessage;
        setTimeout(() => { btn.textContent = label; }, 2500);
    });
}

// ── Boot ──────────────────────────────────────────────────────────────────────
init();
