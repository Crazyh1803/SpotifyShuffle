// auth.js — Spotify PKCE OAuth flow
// Works in any browser. No client secret required.

const SPOTIFY_AUTH_URL = 'https://accounts.spotify.com/authorize';
const SPOTIFY_TOKEN_URL = 'https://accounts.spotify.com/api/token';
const SCOPES = [
    'user-read-private',
    'user-follow-read',
    'user-top-read',
    'user-library-read',
    'playlist-modify-private',
    'playlist-modify-public',
].join(' ');

// The redirect URI is always callback.html, resolved relative to index.html's location.
export function getRedirectUri() {
    return new URL('callback.html', window.location.href).href.split('?')[0];
}

function base64urlEncode(buffer) {
    return btoa(String.fromCharCode(...new Uint8Array(buffer)))
        .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

function generateVerifier() {
    const array = new Uint8Array(64);
    crypto.getRandomValues(array);
    return base64urlEncode(array);
}

async function generateChallenge(verifier) {
    const encoded = new TextEncoder().encode(verifier);
    const digest = await crypto.subtle.digest('SHA-256', encoded);
    return base64urlEncode(digest);
}

// Step 1 — redirect to Spotify login
export async function startAuth(clientId) {
    const verifier = generateVerifier();
    const challenge = await generateChallenge(verifier);
    sessionStorage.setItem('pkce_verifier', verifier);

    const params = new URLSearchParams({
        client_id: clientId,
        response_type: 'code',
        redirect_uri: getRedirectUri(),
        code_challenge_method: 'S256',
        code_challenge: challenge,
        scope: SCOPES,
    });
    window.location.href = `${SPOTIFY_AUTH_URL}?${params}`;
}

// Step 2 — exchange code for tokens (called from callback.html)
export async function exchangeCode(code, clientId) {
    const verifier = sessionStorage.getItem('pkce_verifier');
    if (!verifier) throw new Error('No PKCE verifier found. Please try logging in again.');

    const redirectUri = window.location.href.split('?')[0]; // callback.html's own URL

    const res = await fetch(SPOTIFY_TOKEN_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            grant_type: 'authorization_code',
            code,
            redirect_uri: redirectUri,
            client_id: clientId,
            code_verifier: verifier,
        }),
    });
    if (!res.ok) {
        const err = await res.text();
        throw new Error(`Token exchange failed (${res.status}): ${err}`);
    }
    const data = await res.json();
    sessionStorage.removeItem('pkce_verifier');
    return {
        accessToken: data.access_token,
        refreshToken: data.refresh_token,
        expiresAt: Date.now() + data.expires_in * 1000,
        scope: data.scope,
    };
}

// Refresh an expired access token
export async function refreshAccessToken(refreshToken, clientId) {
    const res = await fetch(SPOTIFY_TOKEN_URL, {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({
            grant_type: 'refresh_token',
            refresh_token: refreshToken,
            client_id: clientId,
        }),
    });
    if (!res.ok) throw new Error('Session expired. Please log in again.');
    const data = await res.json();
    return {
        accessToken: data.access_token,
        refreshToken: data.refresh_token || refreshToken, // Spotify may not always return a new one
        expiresAt: Date.now() + data.expires_in * 1000,
        scope: data.scope,
    };
}
