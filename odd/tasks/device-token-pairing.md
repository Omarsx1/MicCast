# Device token pairing (protocol v0x03)

Objective: replace the ephemeral 6-digit PIN as the only credential with per-device
tokens issued after the first PIN pairing, so reconnecting requires no PIN entry.
Branch: main. Delivery: work-unit commits. Test runner: Python 3 client against the
running macOS app (no Swift test target, no Gradle for Android).

## Specs

- **S1** — After a successful PIN pairing over a v0x03 handshake, the Mac issues 32
  random bytes as a per-device token, stores it keyed by the client's stable device ID
  in a `0600` JSON file under `~/Library/Application Support/MicCast/` (not the
  Keychain, see L3), and returns it in the handshake response.
- **S2** — A later connection authenticates with `HMAC-SHA256(token, nonce)` and no
  PIN entry. The token is bound to the device ID; a token presented by a different
  device ID must fail.
- **S3** — Legacy v0x02 clients (magic + version 0x02 + HMAC(PIN) = 37 bytes) keep
  working unchanged, including the 1+4 byte success response.
- **S4** — The Mac lists paired devices and can revoke one; a revoked token must fail
  the next handshake with status 0x01, and revoking the connected device must drop
  its session.
- **S5** — Failed attempts and lockout survive app restarts (persisted), so killing
  the app no longer resets brute-force protection.
- **S6** — If token auth fails (revoked or stale), the phone falls back to the PIN
  dialog once and re-pairs, replacing the stored token.
- **S7** — Rotating the PIN ("Nuevo PIN") must not silently kill audio for an already
  authenticated session; the session ID is issued per successful handshake, not per
  PIN rotation.

## Protocol v0x03

Client → Mac, after the 16-byte nonce:

```
'MCSC'(4) version=0x03(1) mode(1) idLen(1) deviceId(idLen) nameLen(1) name(nameLen) hmac(32)
mode: 0x00 = PIN, 0x01 = TOKEN
hmac: HMAC-SHA256(PIN or token, nonce)
```

Mac → client on success:

```
0x00(1) sessionId(4, BE) tokenLen(1) token(tokenLen)
tokenLen is 32 only when a new token was issued (PIN mode); otherwise 0.
```

Failure response stays a single status byte (0x01 auth, 0x02 busy, 0x03 locked out).

## Tasks

- **T1** macOS: `PairingStore` (Keychain JSON of paired devices) + token helpers.
- **T2** macOS: v0x03 handshake, token issue/verify, per-handshake session ID,
  persisted lockout. Route: inline.
- **T3** macOS: paired-devices list + revoke in the popover.
- **T4** Android: encrypted `PairingStore` (Keystore AES-GCM) + v0x03 client.
- **T5** Android: auto-connect with token, PIN fallback on failure.
- **T6** Verify with a Python client: legacy v0x02, PIN pairing, token reconnect,
  wrong-device token, revocation, lockout persistence across restart.

## Log

- **L1** User request: "Mi recomendación es B... implementalo" — implement per-device
  tokens and persist the brute-force lockout.
- **L2** Constraint found: no Gradle/`gradlew` in the repo and no Gradle binary
  installed, so the Android changes cannot be compiled or tested on this machine.
  macOS side is verified end-to-end with a Python client.
- **L3** Storage moved from Keychain to a `0600` file: an unsigned/CLI build blocked on
  a Keychain authorization dialog, and ad-hoc re-signing on every `build_app.sh` would
  break the item ACL repeatedly.
- **L4** Verification (14/14): legacy v0x02 accepted, v0x03 PIN issues a 32-byte token,
  token reconnect accepted with no new token, token bound to device id, revoked token
  rejected, 5th failure returns 0x03, lockout survives a server restart. Session id is
  now issued per handshake, so rotating the PIN no longer kills an active stream.
