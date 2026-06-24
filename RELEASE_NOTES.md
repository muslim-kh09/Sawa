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
