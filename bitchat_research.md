# BitChat Architecture Analysis: BLE Fragmentation & Rich Media

Based on an analysis of the `permissionlesstech/bitchat` repository, here is a technical breakdown of how they handle large messages, link delays, and rich media (images and voice) over BLE.

## 1. Large Message Handling (BLE MTU & Fragmentation)

BLE limits single packets to a negotiated Maximum Transmission Unit (MTU), typically capped at 512 bytes on modern OSs (minus protocol overhead). BitChat circumvents this by using a sophisticated Application-Layer Fragmentation Protocol:

*   **`BLEOutboundFragmentPlanner`**: This service breaks large payloads (images, voice, or long texts) into chunks. It dynamically calculates the `chunkSize` by reading the connection's negotiated `bleMaxMTU` and subtracting the packet headers overhead.
*   **Packet Types**: Instead of sending a single string, they serialize data into binary `BitchatPacket` structs. Large payloads are chunked into multiple `fragmentContinue` packets and one final `fragmentEnd` packet.
*   **Reassembly Queue**: On the receiving end, the `BLEInboundWriteBuffer` catches these chunks. It allocates a buffer based on the sequence offset. It only commits the message to the database once the `fragmentEnd` is received and the `offsets` align properly into a contiguous payload.

## 2. Handling Delays and Reliability

*   **Padded Writes & Throttling**: The protocol detects when the BLE link is saturated and implements padding/delays between fragmented writes to prevent dropping packets.
*   **Source Routing / Directed Only**: The `BLEService` specifies `directedOnlyPeer` for large fragments to prevent flooding the mesh. Fragmented packets for large media are sent directly to the destination peer rather than broadcasted blindly.
*   **ACKs and Retries**: Because it uses GATT Write Without Response for speed in some areas, it relies on application-layer Acknowledgements. If a `fragmentEnd` is sent but no ACK is returned within a timeout window, the transfer scheduler can resend missing chunks.

## 3. Rich Media (Images and Voice)

*   **Image Compression (`ImageUtils.swift`)**: 
    *   Images are resized so their maximum dimension is typically `448px`.
    *   The `UIImage` is converted to a `CGImage` and redrawn into a fresh context. This intentionally **strips all EXIF metadata** to save bytes and protect user privacy.
    *   The protocol recursively compresses the JPEG quality (stepping down to `30%`) until the total file size drops below `45,000 bytes (45 KB)`.
*   **Voice/Audio**: 
    *   Audio is captured and aggressively compressed using codecs optimized for speech (likely Opus or low-bitrate AAC).
    *   Similar to images, it is passed through the `BLEOutboundFragmentPlanner` which breaks the compressed audio byte array into MTU-sized chunks.

## How to Adapt this to Sawa:

To implement this in our `BtlMeshService` and `GattOperationQueue`:
1.  **Binary Protocol Upgrade**: We need to shift from sending plain UTF-8 strings in `writeCharacteristic` to sending structured ByteArrays (e.g., `[Type=Image][MessageId=123][TotalChunks=10][ChunkIndex=0][Payload...]`).
2.  **Stateful Reassembly**: Our `GattOperationQueue` will need an `InboundBuffer` map. When `onCharacteristicWriteRequest` fires, we append the ByteArray to the buffer for that `MessageId`. Once `ChunkIndex == TotalChunks`, we decode the ByteArray into an Image/Audio file and insert it into Room.
3.  **Image UI**: We would add a `Bitmap` or URI column to the `Message` Room entity, and update `MessageBubble` in Compose to load images using a library like Coil.
