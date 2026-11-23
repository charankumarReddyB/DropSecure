# Device test plan and result sheets
Record REAL results only. Leave a cell empty if a test was not run. Do not copy numbers from elsewhere.

## A. Test devices
| ID | Make/model | Android version | Wi-Fi Direct works? | BLE advertising works? | Notes |
|----|-----------|-----------------|---------------------|-------------------------|-------|
| P1 |           |                 |                     |                         |       |
| P2 |           |                 |                     |                         |       |
| P3 |           |                 |                     |                         |       |

## B. Functional tests (pass/fail + evidence screenshot/file name)
| # | Test | Steps | Expected | Result |
|---|------|-------|----------|--------|
| F1 | Build | Sync + Make Project + run on P1 | App opens to splash then home | |
| F2 | Unit tests | Run CoreSuiteTest | 23/23 pass | |
| F3 | Permissions | Fresh install, open Wi-Fi Direct > Receive | Explanation screen, then system prompt | |
| F4 | Discovery (Wi-Fi) | P2 Receive; P1 Send > Find devices | P2 listed (name from Wi-Fi Direct, BLE signal) | |
| F5 | Wi-Fi Direct transfer 10 MB | Accept invite, compare code, Accept | Completed, hash verified, file opens identical | |
| F6 | Bluetooth transfer 10 MB | Bluetooth > Receive (discoverable); sender scans | Completed and verified | |
| F7 | Code mismatch | Tap "Doesn't match" on one phone | Transfer stops, nothing saved | |
| F8 | Reject | Receiver taps Reject | Sender shows "declined"; no file | |
| F9 | Cancel | Sender cancels at ~50% | Both stop; partial deleted | |
| F10 | Resume | Send 100 MB; at ~40% switch sender Wi-Fi off / move apart / force-stop app | Interrupted; History shows progress; Resume continues from saved point; final hash OK | |
| F11 | Resume sends less data | Compare "bytes this session" (CSV) before/after resume | Second run sends only the missing part | |
| F12 | Multi-receiver (Bluetooth) | Send one file to P2 and P3 together | Two independent codes, two independent results | |
| F13 | Multi-receiver (Wi-Fi Direct) | Same with Wi-Fi Direct | Runs one after another; both complete | |
| F14 | Low storage | Receiver with very little free space | Offer auto-declined with message | |
| F15 | History | Open History, filter, delete, Clear finished | Works; Resumable items show progress | |
| F16 | Settings | Change device name, save folder, transport | Name appears to senders; file saved in chosen folder | |

## C. Performance (same phones, same files, same distance ~1 m)
Use History > Export CSV after the trials. Run at least 5 trials per cell. Time is measured from "receiver accepted" until the receiver's SHA-256 check passes.
| Transport | File size | Trials run | Successful | Mean MB/s | Min MB/s | Max MB/s | Mean connect time (s) |
|-----------|-----------|-----------|-----------|-----------|----------|----------|-----------------------|
| Wi-Fi Direct | 10 MB  | | | | | | |
| Wi-Fi Direct | 100 MB | | | | | | |
| Wi-Fi Direct | 500 MB | | | | | | |
| Bluetooth    | 10 MB  | | | | | | |
| Bluetooth    | 100 MB | | | | | | |
| Bluetooth    | 500 MB | | | | | | |
Other metrics: discovery time (CSV `discoveryMs`), resume success rate (History summary), integrity failures (History summary).

## D. Battery (optional; only if you can do it reliably)
The app stores battery % at start and end of each transfer, but Android reports whole percent only, so only use it for long transfers.
For a better figure: fully charge, screen brightness fixed, airplane mode + only needed radio, same transfer repeated, note % drop
and elapsed time; repeat 3 times per transport. Record: start %, end %, duration, file size.
