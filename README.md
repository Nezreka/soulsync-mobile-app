# SoulSync Mobile App (`soulsync-mobile-app`)

Standalone multi-platform mobile client repository for **SoulSync**, built against **DESIGN-CONNECT v0** (including the Nov 21, 2026 **Option B** amendment).

## Repository Layout

- [`android/`](android/) — **v1 Music-First** native Android client (Kotlin, Jetpack Compose, Material 3, AndroidX Media3 `ExoPlayer` + `MediaSessionService`, Android TV D-pad readiness, `minSdk = 26`, `compileSdk = 35`).
- [`ios/`](ios/) — Placeholder for the future native Swift/SwiftUI iOS client built against the same spec and conformance vectors.
- [`spec/`](spec/) — Shared protocol reference and drop-in location for `conformance-vectors.json`.

## Hard Architectural Boundaries

- **Zero cloud playback dependency:** After pairing, all browsing and audio streaming go 100% direct to the user's SoulSync server over cert-pinned HTTPS. A `connect.ssync.net` outage must never interrupt playback.
- **No MetaSync / SIN calls:** This repository must never call `metasync.ssync.net`, SIN, or bundle any server-side code.
- **Zero raw code leakage:** Pairing codes travel only `user -> app` and `app -> SoulSync` (direct cert-pinned redemption). `connect.ssync.net` receives only `code_hash` (`SHA-256`) and an ephemeral X25519 public key.
- **Secret hygiene:** `lookup_id`, raw pairing codes, and `device_token` are redacted from logs/`toString()` and stored encrypted via Android Keystore (`AES-256-GCM`).

## Android Module Structure

| Module | Type | Responsibility |
| :--- | :--- | :--- |
| `:core:connect` | Pure Kotlin/JVM | `PairingCode`, `ServerIdentity`, `IpLabelCodec`, `ConnectProtocol`, Option B `RendezvousAnswerVerifier`, `RendezvousClient`, conformance vector tests |
| `:core:storage` | Android Library | `KeystoreServerVault` (`AndroidKeyStore` AES-256-GCM encrypted file, atomic write) |
| `:core:network` | Android Library | `CertPinningOkHttp` (leaf cert SHA-256 pin + Bearer token), `CandidateAddressRacer` (LAN-first), `AdmitClient` |
| `:core:player` | Android Library | `MusicApiClient` (direct SoulSync music endpoints) + `SoulSyncPlaybackService` (Media3 `ExoPlayer` + `MediaSessionService`) |
| `:feature:pairing` | Android Library | Compose pairing flow, 12-char fingerprint verify card, Admit profile-pick UI, `dpadFocusable` |
| `:app` | Android App | Compose root, `MusicHomeScreen`, `NowPlayingBar`, `MediaController` host, Leanback-optional manifest |

## Quick Start (Android)

Requires JDK 17 and Android SDK (API 35).

```bash
cd android
./gradlew :core:connect:test
./gradlew testDebugUnitTest
```

## Conformance Vector Gate

Drop Muse's `conformance-vectors.json` into:
- `spec/conformance-vectors.json`
- `android/core/connect/src/test/resources/conformance-vectors.json`

Then wire the concrete RFC 9180 HPKE opener, exact `info` byte layout, and inner envelope codec in `:core:connect` and verify 100% pass in `ConformanceVectorsTest` before testing against `https://staging.connect.ssync.net`.
