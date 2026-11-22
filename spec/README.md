# DESIGN-CONNECT v0 Specification & Conformance Vectors

This directory holds the shared protocol contract and conformance test vectors (`conformance-vectors.json`) used across all native SoulSync mobile clients (`android/` and future `ios/`).

## Spec Amendment & DNS Label Correction (Nov 21, 2026)

- **HPKE Suite:** RFC 9180 Base Mode — `DHKEM(X25519, HKDF-SHA256)` (`0x0020`), `HKDF-SHA256` (`0x0001`), `ChaCha20-Poly1305` (`0x0003`).
- **Wire format:** Raw single-shot `enc || ciphertext` (no Tink keyset prefix), empty AAD (`""`).
- **HPKE `info` layout:** ASCII `"soulsync-connect-hpke-info-v1\n"` followed by `code_hash` (lowercase hex).
- **Inner signed envelope:** JSON `{"payload": {...}, "signature": "<unpadded base64url Ed25519 signature>"}` where `signature` signs the compact canonical JSON bytes of `payload`.
- **Option B verification pipeline:**
  1. Construct HPKE `info` from `code_hash` only.
  2. Open `enc || ciphertext` with the app's ephemeral X25519 private key + `info`.
  3. Verify the inner Ed25519 signature over `payload` using `payload.server_pubkey`.
  4. Recompute `derived_lookup_id = lowercase hex SHA-256(raw 32-byte Ed25519 pubkey)` and assert constant-time equality with `payload.lookup_id`.
  5. Assert constant-time equality `payload.code_hash == requested_code_hash`.
- **DNS ID correction:** Hostnames use `dns_id = lookup_id.take(32)` (first 32 lowercase hex chars of `lookup_id`), since DNS labels max out at 63 characters:
  - Production IPv4: `a-b-c-d.<dns_id>.connect.ssync.net`
  - Production IPv6: `<32-char lowercase hex>.<dns_id>.connect.ssync.net`
  - Wildcard: `*.<dns_id>.connect.ssync.net`
  - Staging: `*.<dns_id>.staging.connect.ssync.net`
  - Off-DNS (protocol headers, sealed payload, identity storage): always use the full 64-char `lookup_id`.
