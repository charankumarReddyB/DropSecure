# DropSecure 🛡️📱

> **Offline, end-to-end encrypted peer-to-peer file sharing for Android**  
> High-performance transfers over **Wi-Fi Direct** and **Bluetooth** with zero internet dependency, zero tracking, and mathematical forward secrecy.

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.22-purple.svg?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-green.svg?logo=android)](https://android.com)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20(Material%203)-blue.svg)](https://developer.android.com/jetpack/compose)
[![Tests](https://img.shields.io/badge/Tests-23%20Passing-brightgreen.svg)]()
[![Security](https://img.shields.io/badge/Crypto-AES--256--GCM%20%7C%20ECDH%20%7C%20HKDF-red.svg)]()

---

## 📖 Overview

**DropSecure** is a secure, completely offline peer-to-peer file transfer system engineered for Android. It operates independently of cellular networks, Wi-Fi routers, or central cloud infrastructure.

By combining **Bluetooth Low Energy (BLE)** for sub-second discovery, **Autonomous Wi-Fi Direct (Wi-Fi P2P)** for high-speed transfers (up to 20–30 MB/s), and **Bluetooth Classic (RFCOMM)** for universal device compatibility, DropSecure provides a seamless and private sharing experience.

Every transfer is secured with ephemeral **ECDH (Curve25519)** key exchange, **HKDF-SHA256** derivation, **AES-256-GCM** authenticated chunk encryption, and a **Short Authentication String (SAS)** 6-digit confirmation code to prevent Man-in-the-Middle (MITM) attacks.

---

## ✨ Features

- 🚫 **100% Offline & Private**: Zero cloud servers, zero analytics, zero network tracking. Never transmits data to the internet.
- ⚡ **Dual Transport Engines**:
  - **Wi-Fi Direct**: High-throughput transfers (autonomous P2P groups) ideal for large videos, images, documents, and APKs.
  - **Bluetooth Classic (RFCOMM)**: Universal fallback with zero pairing friction.
- 🔍 **Instant Hybrid Discovery**:
  - Receiver embeds its Classic MAC and identity into optimized BLE advertisement packets (< 31 bytes).
  - Senders detect receivers in **under 1 second** without manual pairing or scanning delays.
- 🔒 **End-to-End Cryptography**:
  - **Ephemeral Diffie-Hellman (Curve25519)**: Independent session keys generated per transfer.
  - **AES-256-GCM**: Authenticated encryption for every chunk preventing data tampering and replay attacks.
  - **Short Authentication String (SAS)**: 6-digit commitment code compared on both screens to defeat active MITM adversaries.
  - **SHA-256 Integrity Verification**: End-of-file verification before writing to device storage.
- 🔁 **Resumable Transfers**:
  - Bitmap-based chunk tracking backed by **Room/SQLite**.
  - Interrupted transfers automatically resume from the exact last saved byte without re-sending the whole file.
- 🎨 **Modern Jetpack Compose UI**:
  - Clean Material 3 design, real-time speed (MB/s) and ETA tracking, transfer history, and one-click CSV benchmark export.

---

## 🏗️ Project Architecture

```
app/src/main/java/com/example/dropsecure/
├── core/                  # Pure Kotlin Cryptographic & Transfer Protocol (No Android dependencies)
│   ├── Handshake.kt       # ECDH (Curve25519) + commitment + HKDF-SHA256
│   ├── Hkdf.kt            # RFC 5869 Key Derivation Function implementation
│   ├── Session.kt         # AES-256-GCM encrypted record framing
│   ├── FileSender.kt      # Encrypted chunk streaming pipeline
│   ├── FileReceiver.kt    # Chunk sink, verification & reassembly
│   ├── ChunkBitmap.kt     # Efficient bitset tracking for resume states
│   └── OfferCodec.kt      # Binary protocol framing for file metadata
│
├── transport/             # Radio Hardware Abstraction Layer
│   ├── WifiDirectTransport.kt     # Wi-Fi Direct Autonomous Group & TCP socket engine
│   ├── BluetoothTransport.kt      # Bluetooth Classic RFCOMM (Insecure & Secure sockets)
│   ├── BluetoothAddressResolver.kt# Hardware MAC resolution for seamless BLE handoff
│   └── Link.kt                    # Generic transport link stream interface
│
├── discovery/             # Hybrid Discovery Subsystem
│   ├── BleAdvertiser.kt   # BLE advertisement with payload packing (<= 31 bytes)
│   ├── BleScanner.kt      # Low-latency BLE scanner with hardware filter bypass
│   ├── DiscoveryController.kt # Multi-transport merger & address resolution
│   └── BleConstants.kt    # UUIDs, timeouts, and protocol identifiers
│
├── manager/               # Transfer Orchestration & State Machine
│   ├── TransferManager.kt # Lifecycle management, queue gating, concurrency control
│   ├── TransferService.kt # Foreground service preserving active transfers
│   └── Models.kt          # UI transfer models and stage transitions
│
├── data/                  # Persistence Layer
│   ├── AppDatabase.kt     # Room database definition
│   ├── TransferDao.kt     # Transfer history & resume queries
│   └── TransferEntity.kt  # Database schema for transfer states
│
├── permissions/           # OS Permission Management
│   └── PermissionHelper.kt# Granular Android 8 through 16 permission handling
│
└── ui/                    # Jetpack Compose Interface
    ├── Screens1.kt        # Home, Send, Discovery, Receive, and Flow screens
    ├── Screens2.kt        # Transfer History, Benchmark metrics & Settings
    ├── Common.kt          # Reusable Material 3 design widgets
    └── Logo.kt            # DropSecure vector branding
```

---

## 🧪 Automated Testing

DropSecure includes a comprehensive standalone test suite in pure Kotlin that validates cryptographic security and error recovery without requiring connected phones or Android emulators:

```powershell
# Run the complete test suite
.\gradlew.bat test
```

### Verified Test Cases (`CoreTestSuite.kt`):
1. **RFC 5869 Test Vectors**: HKDF-SHA256 verification against cryptographic test suites.
2. **Session Key Agreement**: Both parties independently derive identical session keys and SAS verification codes.
3. **MITM Detection**: Tampering with ECDH public keys results in mismatched 6-digit confirmation codes.
4. **Tamper Resistance**: Modifying ciphertext in transit causes AES-GCM authentication tag failure.
5. **Replay & Reorder Defense**: Out-of-order or duplicate packets are instantly rejected.
6. **Resumable Chunks**: Simulates connection drop mid-transfer and resumes from the exact saved bitmap index.
7. **Corrupted Partial Handling**: Corrupted partial blocks are discarded and re-requested.

---

## 🚀 Building & Running

### Requirements
- **Android Studio Ladybug (2024.2+)** or newer
- **JDK 17**
- **Android SDK Platform 34+**
- Minimum supported Android version: **Android 8.0 (API 26)**

### Compile Debug APK
```powershell
.\gradlew.bat assembleDebug
```
The compiled APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

### Install via ADB
```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📱 How to Use

### Mode 1: Wi-Fi Direct (High Speed)
1. Turn **Wi-Fi** and **Location (GPS)** ON on both phones.
2. **Receiver**: Open DropSecure > **Wi-Fi Direct > Receive**.
3. **Sender**: Open DropSecure > **Wi-Fi Direct > Send**, choose a file, and tap **Find nearby devices**.
4. Tap the receiver's name from the list and hit **Send**.
5. **Compare the 6-digit verification code** on both screens and tap **Confirm**.
6. The receiver phone taps **Accept** to save the file into Downloads.

### Mode 2: Bluetooth (Everywhere Compatibility)
1. Turn **Bluetooth** and **Location (GPS)** ON on both phones.
2. **Receiver**: Open DropSecure > **Bluetooth > Receive** (tap *Allow* on the visibility prompt).
3. **Sender**: Open DropSecure > **Bluetooth > Send**, select a file, pick the receiver, and tap **Send**.
4. Compare the 6-digit verification code and transfer!

---

## 👥 Authors & Academic Context

Developed as the **Mobile Computing (ITA0304)** Capstone Project, Team 6:
- **Developer**: B. Charan Kumar Reddy ([@charankumarReddyB](https://github.com/charankumarReddyB))
- **Email**: charankumarreddybantrothula@gmail.com
- **Institution**: SIMATS Engineering, Saveetha Institute of Medical and Technical Sciences
- **Supervisor**: Dr. Almas Begum

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).
