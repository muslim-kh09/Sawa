## v2.0.4
- **BitChat Pacing & Stability:** Incorporated the official BitChat iOS logic to pause background BLE scanning during large transfers to eliminate radio time-multiplexing drops, and introduced lightweight buffer draining delays.
- **Dynamic BLE Timeouts:** Replaced the global rigid GATT timeout with a dynamic duration scaled based on payload chunk size, guaranteeing large offline files won't prematurely terminate.
- **Safe MTU Discovery:** Enforced safe MTU transmission tracking, preventing large packets from being silently dropped by strictly matching the receiver's negotiated capabilities.
- **UI "Sent" Status Fix:** Resolved a race condition in the asynchronous peer queue that prevented the single checkmark `STATUS_SENT` from reliably appearing when sending to multiple peers.

## v2.0.3
- **Architectural Reform:** Executed a massive network layer overhaul by successfully smashing the fatal 5-minute GATT timeout and slashing it down to 15 seconds.
- **Background Architecture:** Permanently deleted the naive 15s radio-shutdown battery saver loop, successfully moving to OS-native `SCAN_MODE_LOW_POWER`.
- **Strict Binary Routing:** Eradicated the plaintext legacy UI packet leak by introducing strict byte-header typing (`0x01` Chat, `0x02` Sync), ensuring encrypted identity payloads no longer bleed into the public chat UI.
- **Fragmentation Reboot (Phase 2):** Unified the chunking logic into a robust BitChat-inspired resilient BLE Mesh protocol, dropping redundant implementations.
- **Strict Chunking Write:** Enforced `WRITE_TYPE_DEFAULT` (Write with Response) for all outgoing fragmentation payloads, eliminating arbitrary thread delays.
- **Memory Safety:** Implemented strict 30s timeout tracking on the inbound payload assembly buffer to automatically self-clear dropped connections and prevent memory leaks.

## v2.0.2
- **Arabic Language Default:** The app now strictly enforces Arabic language and right-to-left (RTL) layout by default for all users, unless their device is explicitly set to English.
- **In-App Language Selector:** Added a convenient language toggle directly inside the Settings menu, allowing users to switch between "عربي" and "English" dynamically.

## v2.0.1
- **Large Payload Support:** Upgraded the core packet fragmentation engine (`PacketFragmenter`) to use 16-bit indexing, increasing the maximum theoretical payload size to ~720KB (supporting up to 65,535 fragments).
- **UI Bug Fixes:** Fixed a subtitle formatting bug on the chat screen that displayed a raw string placeholder.

## v2.0.0
- **Premium Apple-Inspired Interface:** Completely removed the Brutalist design language in favor of a high-end, iOS-inspired aesthetic. The app now features perfectly rounded components, soft surface shadows, intuitive iOS-style navigation, pill-shaped input fields, and pristine typography for a universally welcoming user experience.
- **Arabic Localization:** Implemented full RTL compatibility and comprehensive Arabic translations across all screens to seamlessly support Arabic-speaking users.

## v1.1.3
- **Feature Deprecation:** Completely removed Voice Messaging and all related permissions/code from the app per user request.

## v1.1.2
- **Awwwards-Tier UI Overhaul:** Rebuilt `ChatScreen` with premium "Double-Bezel" (Doppelrand) nested architecture, offering an agency-level aesthetic with haptic kinetic tension animations.
- **Fluid Motion:** Replaced generic spring animations with custom `CubicBezier` easing curves that mimic real-world mass and spring physics.
- **Mic Button Hotfix:** Added a safety check for quick-taps on the voice recording button. Instantly failing to hold the mic now gracefully discards the broken empty payload and warns the user instead of silently failing.

## v1.1.1
- Added Native Interactive Voice Player UI that supports play/pause and progress tracking.
- Fixed an issue where voice recordings would fail to send because of missing internal `cache-path` in `FileProvider`.
- Vastly improved support for sending Voice and Long Text messages by removing the aggressive 4-second GATT timeout, allowing MTU fragmented payloads the time they need to transfer across the mesh.

## v1.1.0
- **Architectural Overhaul:** Integrated the new `BinaryProtocol` and `BleFragmentation` engine to support robust byte-level data serialization over BLE MTU limits.
- **Media Support:** Sawa now natively supports sending and receiving Images and Voice over the decentralized mesh.
- **Aggressive Media Compression:** Added a recursive `ImageUtils` pipeline to automatically downscale and compress images under 45KB.
- **iOS Animations:** Navigation between chat and settings now features buttery smooth Apple-style `AnimatedContent` horizontal sliding.

## v1.0.9
- **Chat Layout Refactored:** Completely fixed the input bar keyboard overlap issue. The chat interface now perfectly hugs the top of the keyboard when it is opened.
- **UI Simplification:** Removed the experimental Glassmorphism UI and AMOLED theme to provide a cleaner and more stable core experience.

## v1.0.8
- **Core Engine Hotfix:** Addressed a critical `SecurityException` startup crash affecting all devices by explicitly declaring the `WAKE_LOCK` permission.
- **OTA Reliability:** Auto-updates are now seamlessly handled by native DownloadManager, eliminating parsing errors and bridging the gap between programmatic and manual notification installs.

## v1.0.7
- **Core Engine Upgrade:** Upgraded `BtlMeshService` to a bulletproof Foreground Service utilizing a `PARTIAL_WAKELOCK` to prevent background CPU/BLE standby and ensure persistent connectivity.
- **Universal Device Synchronization:** Enforced `TRANSPORT_LE` explicit bridging across all GATT connections, resolving asymmetric hardware issues and enabling seamless communication between Android 7 and Android 14 devices.
- **Verified Delivery Checkmarks:** Stopped false positive message delivery status. The UI double-checkmark now strictly links to the hardware `BluetoothGatt.GATT_SUCCESS` callback, providing real-time verified delivery status.
- **Premium Bubble Glassmorphism UI:** Overhauled the Chat screen with a highly premium bubble glass layout featuring organic `24.dp` high corner rounding, dynamic light/dark alpha background profiles, and a thin glowing stroke.
- **IME Keyboard Sync:** Bound the message scrolling `LazyColumn` dynamically to `WindowInsets.ime` to ensure the chat log always docks smoothly above the input bar when the keyboard is summoned.
- **Scrollable Update Dialog:** The OTA update dialog now natively supports vertical scrolling for extended changelogs.
- **Hybrid App-Lock Security:** Integrated a custom-engineered minimalist PIN Setup/Input screen that activates seamlessly as a fallback on devices without a native screen lock (via `KeyguardManager.isDeviceSecure()`).
- **Application Version:** Bumped stable engine version to v1.0.7.

## v1.0.6
- **API 24 Initialization Fix (Part 2):** Resolved a secondary startup crash on Android 7 caused by attempting to instantiate `NotificationChannel` (which was introduced in Android 8/API 26). The channel creation is now correctly guarded by an SDK version check.
- **Glassmorphism Rebuilt:** The glass layer is now an authentic mathematical frosted UI, using carefully calculated translucent alphas and fine borders to dynamically reflect light across Light, Dark, and AMOLED profiles. Active message lists now natively scroll underneath the alpha-blended TopAppBar and MessageInputBar.
- **Settings UI Polished:** Appearance text buttons in the Settings screen now utilize smooth horizontal scrolling, fully preventing structural text wrapping and overflows.
- **Header Synchronization:** Addressed the "Unknown Peer" header glitch in private direct messaging by properly aligning the global device fingerprint (`fullId`) against local peer caching databases, ensuring accurate username display.
- **Application Version:** Bumped stable engine version directly to v1.0.6.
