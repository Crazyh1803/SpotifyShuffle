// app.js — Main application logic for True Shuffle Web
// Orchestrates auth, API calls, track pool building, shuffle engine, and Spotify save.

import { startAuth, getRedirectUri } from './auth.js';
import { tokens, settings, gapCache, playlistId, history, clearAll } from './storage.js';
import * as api from './api.js';
import { buildPlaylist } from './engine.js';

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

// Store only the fields the engine needs — keeps localStorage cache small
function minifyTrack(t) {
    return {
        id:          t.id,
        name:        t.name,
        duration_ms: t.duration_ms,
        popularity:  t.popularity ?? 0,
        artists:     (t.artists || []).map(a => ({ id: a.id, name: a.name })),
    };
}

// ── Init ──────────────────────────────────────────────────────────────────────

async function init() {
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

async function buildFlow() {
    showScreen('screen-building');
    setProgressText('');

    try {
        const s = settings.get();

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

        function addTrack(track) {
            if (!track?.id || !track.artists?.length) return;
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

        // Source 3: saved album tracks (batch to stay within rate limits)
        if (savedAlbums.length > 0) {
            setStatus(`Expanding ${savedAlbums.length} saved album${savedAlbums.length === 1 ? '' : 's'}…`);
            for (let i = 0; i < savedAlbums.length; i++) {
                const album = savedAlbums[i];
                if (i % 10 === 0) setProgressText(`${i + 1} / ${savedAlbums.length}`);
                try {
                    const res = await api.getAlbumTracks(album.id);
                    for (const t of res.items) {
                        addTrack({ ...t, album: { id: album.id, name: album.name, images: album.images } });
                    }
                } catch { /* skip on error */ }
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

            const gapArtists = followedArtists.filter(a => !tracksByArtist[a.id]);

            if (gapArtists.length > 0) {
                const cache    = gapCache.get();
                const BATCH    = 50;   // ~10-20 s; safe under Spotify rate limits

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
                const toScan    = [...unscanned, ...stale].slice(0, BATCH);

                if (toScan.length > 0) {
                    setStatus(`Scanning ${toScan.length} artist${toScan.length === 1 ? '' : 's'} for deep cuts…`);
                    const newCache = { ...cache };
                    let scanned = 0;
                    let rateLimited = false;

                    for (const artist of toScan) {
                        if (rateLimited) break;
                        setProgressText(`${scanned + 1} / ${toScan.length}`);
                        try {
                            const res    = await api.getArtistTopTracks(artist.id);
                            const tracks = (res.tracks || []).map(minifyTrack);
                            for (const t of tracks) addTrack(t);
                            newCache[artist.id] = { tracks, scannedAtMs: Date.now() };
                            if (!topIds.has(artist.id) && tracks.length > 0) {
                                discoveryIds.add(artist.id);
                            }
                        } catch (e) {
                            if (e.status === 429) {
                                rateLimited = true;
                            }
                            // all other errors: skip this artist silently
                        }
                        scanned++;
                    }

                    gapCache.save(newCache);
                    setProgressText('');

                    // Show scan progress on success if library not fully scanned
                    const totalGap   = gapArtists.length;
                    const totalCached = Object.keys(newCache).filter(id => followedIds.has(id)).length;
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
        const cooldownN = s.cooldownPlaylists ?? 2;
        const { trackIds: cooldownTrackIds, artistIds: cooldownArtistIds } =
            history.getCooldownSets(cooldownN);

        const result = buildPlaylist({
            followedArtists:   effectiveFollowedArtists,
            topIds,
            tracksByArtist:    effectiveTracksByArtist,
            discoveryIds,
            likedIds,
            cooldownTrackIds,
            cooldownArtistIds,
            discoveryBias:     s.discoveryBias     ?? 60,
            targetDurationMs:  s.playlistDurationMs ?? 2 * 60 * 60 * 1000,
        });

        if (result.tracks.length === 0) {
            showError('Could not build a playlist — your library may be too small. Try Again after adding more songs.');
            return;
        }

        // ── Step 5: Save to Spotify ───────────────────────────────────────────
        setStatus('Saving playlist to Spotify…');
        const uris = result.tracks.map(t => `spotify:track:${t.id}`);
        const desc = `Built by True Shuffle • ${new Date().toLocaleDateString()}`;
        let pid = playlistId.get();
        let playlistUrl;

        if (pid) {
            try {
                // Replace first 100 tracks, then append the rest
                await api.replacePlaylistTracks(pid, uris.slice(0, 100));
                for (let i = 100; i < uris.length; i += 100) {
                    await api.addTracksToPlaylist(pid, uris.slice(i, i + 100));
                }
                await api.followPlaylist(pid);
                playlistUrl = `https://open.spotify.com/playlist/${pid}`;
            } catch {
                pid = null;  // fall through to create a new playlist
            }
        }

        if (!pid) {
            const pl = await api.createPlaylist(user.id, 'True Shuffle', desc);
            playlistId.save(pl.id);
            pid = pl.id;
            await api.replacePlaylistTracks(pid, uris.slice(0, 100));
            for (let i = 100; i < uris.length; i += 100) {
                await api.addTracksToPlaylist(pid, uris.slice(i, i + 100));
            }
            await api.followPlaylist(pid);
            playlistUrl = `https://open.spotify.com/playlist/${pid}`;
        }

        // Record to cooldown history
        history.record(result.tracks, cooldownN);

        // ── Step 6: Show success ──────────────────────────────────────────────
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
        if (sp && sp.scanned < sp.total && !likedSongsOnlyMode) {
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

document.getElementById('btn-settings')?.addEventListener('click', () => {
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

    // Cooldown playlists stepper
    const cooldownEl    = document.getElementById('cooldown-value');
    const cooldownDecEl = document.getElementById('btn-cooldown-dec');
    const cooldownIncEl = document.getElementById('btn-cooldown-inc');
    let cooldownVal = s.cooldownPlaylists ?? 2;
    function updateCooldownUI() {
        if (cooldownEl) cooldownEl.textContent = cooldownVal === 0 ? 'Off' : `${cooldownVal}`;
        if (cooldownDecEl) cooldownDecEl.disabled = cooldownVal === 0;
        if (cooldownIncEl) cooldownIncEl.disabled = cooldownVal === 5;
    }
    cooldownDecEl?.addEventListener('click', () => {
        cooldownVal = Math.max(0, cooldownVal - 1);
        settings.save({ cooldownPlaylists: cooldownVal });
        updateCooldownUI();
    });
    cooldownIncEl?.addEventListener('click', () => {
        cooldownVal = Math.min(5, cooldownVal + 1);
        settings.save({ cooldownPlaylists: cooldownVal });
        updateCooldownUI();
    });
    updateCooldownUI();

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

    // Change client ID link
    document.getElementById('btn-change-client-id')?.addEventListener('click', () => {
        document.getElementById('settings-overlay')?.classList.remove('open');
        document.getElementById('input-client-id').value = settings.get().clientId;
        showScreen('screen-setup');
    });
}

// ── Boot ──────────────────────────────────────────────────────────────────────
init();
