<div align="center">
  <img src="banner.png" alt="Sawa Mesh Banner" width="100%">
  <br><br>
  
  <img src="https://raw.githubusercontent.com/muslim-kh09/Sawa/main/app/src/main/res/mipmap-xxxhdpi/ic_launcher.webp" width="128" alt="Sawa Logo">
  <h1>Sawa Mesh (سوا)</h1>
  <p><strong>Off-Grid, Decentralized Bluetooth Low Energy (BLE) Messenger for Android</strong></p>
  <p>Communicate securely anywhere, anytime—no internet, no cell towers, no central servers.</p>

  > ⚠️ **DISCLAIMER:** This application was coded by AI. It is currently in an incomplete, experimental phase and is intended purely for testing and proof-of-concept purposes. Do not rely on it for critical communications yet.

  [![Kotlin](https://img.shields.io/badge/Kotlin-1.9.0-blue.svg)](https://kotlinlang.org)
  [![Android API](https://img.shields.io/badge/API-24%2B-brightgreen.svg?style=flat)](https://android-arsenal.com/api?level=24)
  [![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Ready-blueviolet.svg)](https://developer.android.com/jetpack/compose)
  [![Room](https://img.shields.io/badge/Room-Database-blue.svg)](https://developer.android.com/training/data-storage/room)
</div>

<br>

Sawa is a high-performance, decentralized BLE mesh messenger inspired by architectures like BitChat and Briar. It allows users to chat in environments entirely devoid of traditional internet infrastructure by securely bouncing messages from phone to phone.

## ✨ Features

- **No Internet Required:** Relies entirely on Bluetooth Low Energy (BLE) and the Store-Carry-Forward protocol.
- **End-to-End Encrypted (E2EE):** Built on the Noise Protocol (XX pattern) using Google Tink for uncompromised cryptographic security (`X25519`, `Ed25519`, `ChaCha20-Poly1305`).
- **Global & Private Channels:** Broadcast to the whole mesh or engage in secure, encrypted 1-to-1 direct messages (solo chats).
- **Anti-Replay Routing Engine:** Implements advanced mesh routing logic to drop duplicates, prevent replay attacks, and manage TTL (Time-To-Live) network floods.
- **Golomb-Coded Set (GCS) Sync:** Ultra-efficient, compressed ledger anti-entropy algorithm to rapidly sync vector clocks without overwhelming the 512-byte GATT MTU limits.
- **Panic Mode (وضع الذعر):** A built-in kill switch to instantly purge all in-memory cryptographic keys and delete the Room database with a 3-tap mechanism.
- **SOS Broadcasting:** Automatically pulls precise GPS coordinates and broadcasts priority SOS beacons across the mesh network in emergencies.

---

## 🏗️ Architecture

Sawa is built natively using **Kotlin**, **Jetpack Compose**, and **Hilt (Dagger)**. The mesh layer operates entirely within an Android Foreground Service.

### 1. BLE Transmission Layer
- **GattOperationQueue:** Implements strict Kotlin `Mutex` locks and sequential dispatching (`limitedParallelism(1)`) to avoid BLE stack race conditions.
- **MTU Fragmentation:** Dynamically slices payloads using `PacketFragmenter` to bypass classic BLE MTU limits (supporting up to 512 bytes).
- **Store-Carry-Forward:** Devices buffer messages in a local Room ledger and aggressively relay them when coming into contact with new peers.

### 2. Cryptographic Identity
- The `IdentityManager` automatically generates a decentralized identity on first launch.
- Keys are wrapped securely using the hardware-backed `AndroidKeyStore`.
- No central PKI (Public Key Infrastructure). Every user is identified purely by their Ed25519 Public Fingerprint.

### 3. TLV Packet Engine
Data is serialized using a custom Type-Length-Value (TLV) engine to guarantee binary compactness. 
- `0x01` — Announcement Packets
- `0x02` — Public Broadcast Packets
- `0x03` — E2EE Private Messages

---

## 🚀 Getting Started

### Prerequisites
- Android Studio Iguana (or newer)
- Minimum SDK: API 24 (Android 7.0)
- Target SDK: API 34 (Android 14)

### Building the Project
1. Clone the repository:
   ```bash
   git clone https://github.com/muslim-kh09/Sawa.git
   ```
2. Open the project in Android Studio.
3. Sync Gradle and build the project. Note that you MUST test this on physical Android devices. **BLE mesh functionality cannot be tested properly on the Android Emulator.**

### Permissions Required
The app handles dynamic permission requests on launch. Due to Android OS restrictions, the following are required for BLE advertising and scanning:
- `ACCESS_FINE_LOCATION`
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`

---

## 🐞 Known Limitations & Roadmap
- **iOS Compatibility:** Currently Android-only. A Kotlin Multiplatform (KMP) or Swift equivalent is required for cross-platform bridging.
- **Background Execution Limits:** Android may throttle BLE scans when the app is backgrounded. Ongoing work focuses on adapting Android 14+ foreground service types to improve persistence.

## 🤝 Contributing
Contributions are highly encouraged! Whether it's optimizing the `GcsFilter` sync logic, improving UI animations in Jetpack Compose, or adding battery-efficiency algorithms—feel free to open a Pull Request.

## 📄 License
This project is licensed under the MIT License.
