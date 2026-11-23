# Security design: threats, countermeasures, evidence

| Threat | Countermeasure | Code | Automated test |
|---|---|---|---|
| Eavesdropping | App-level AES-256-GCM on every chunk, independent of Wi-Fi/Bluetooth link security | core/Session.kt | transfer round-trip tests |
| Weak/reused keys | Fresh ephemeral ECDH P-256 per connection; HKDF-SHA256; separate key per direction; keys wiped after use | core/Handshake.kt | "every session uses fresh ephemeral keys"; HKDF RFC 5869 vector |
| Peer impersonation / MITM | Commitment-based verification code (SAS) derived from the full handshake transcript; user must compare | Handshake.kt, ui PairingPanel | "man-in-the-middle ends up with DIFFERENT codes"; "rejects a key reveal that does not match the commitment" |
| Invalid curve / bad keys | Point must be on P-256 and in range | Handshake.decodePublic | "off-curve, out-of-range and malformed public keys are rejected" |
| File tampering | GCM authentication per chunk + whole-file SHA-256 before saving | Session.kt, FileReceiver.kt | "tampered chunk is rejected"; "wrong SHA-256 is caught" |
| Replay / reorder / drop | Strict per-direction record counter used as the nonce; session id, record type, chunk index and transfer id are authenticated data | Session.kt | "tamper, duplicate, swap and drop are all rejected" |
| Nonce reuse | Counter incremented before every encryption; one key per direction; new session = new keys (also on resume) | Session.send | "nonces are unique for 200,000 records" |
| Unwanted files | Receiver must tap Accept after seeing name and size; free-space check | TransferManager.decideOffer | "receiver can reject the offer; nothing is written" |
| Malformed input | Validated frame length, offer size/chunk size/name, chunk index, chunk length, bitmap length | Session.kt, OfferCodec.kt, FileReceiver.kt | oversized frame, offers, chunk index/length tests |
| Path traversal in file names | Names sanitised, files written only to app-private temp then saved with a safe name | OfferCodec.sanitizeFileName | "file names cannot escape the target folder" |
| Resource exhaustion | Max file size 4 GB, chunk size 1 KB-1 MB, bounded buffers, streaming I/O, concurrency limits (Wi-Fi 1, BT 3, receive 1) | TransferConfig, TransferManager | offer-limit tests |
| Unsafe resume | A chunk is recorded as done only after write + fsync; progress is a bitmap, not a byte offset; final SHA-256 catches corrupted partial data | FileReceiver.kt | "resume sends only the missing chunks"; "corrupted partial file is caught" |
| Storage exposure | Temp parts in app-private storage; deleted after save, cancel or hash failure; no plaintext keys stored | FileSaver, TransferManager | "partial data is deleted" |

## Honest limitations
* No long-term identity keys: authentication relies on the user comparing the code every time.
* A 6-digit code is short; the commitment makes grinding infeasible, but a distracted user who taps "match" without looking gets no protection.
* Metadata (file name, size) is encrypted, but traffic timing and total size are observable by a nearby radio attacker.
* The Android runtime's own JCA provider performs the crypto; no custom primitives are implemented.
* Denial of service by a nearby device is possible (it can request a connection); the receiver must still approve the code and the file.
