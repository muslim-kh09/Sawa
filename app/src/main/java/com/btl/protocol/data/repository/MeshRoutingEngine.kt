package com.btl.protocol.data.repository

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

// ══════════════════════════════════════════════════════════════════════════════
// Message status constants
// ══════════════════════════════════════════════════════════════════════════════

const val STATUS_PENDING = 0    // Inserted locally, not yet transmitted
const val STATUS_SENT = 1       // GATT write acknowledged by peer
const val STATUS_DELIVERED = 2  // Message confirmed received (for future ACK protocol)

// ══════════════════════════════════════════════════════════════════════════════
// Room Entities
// ══════════════════════════════════════════════════════════════════════════════

@Entity(tableName = "packet_ledger")
data class PacketLedgerEntity(
    @PrimaryKey val packetId: String,
    val senderHash: String,
    val sequenceNumber: Int,
    val expiryTimestamp: Long,
    val payloadBlob: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is PacketLedgerEntity && packetId == other.packetId
    override fun hashCode(): Int = packetId.hashCode()
}

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val isMe: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: Int = STATUS_PENDING,
    val senderName: String? = null
)

// ══════════════════════════════════════════════════════════════════════════════
// DAOs
// ══════════════════════════════════════════════════════════════════════════════

@Dao
interface PacketLedgerDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketLedgerEntity): Long

    @Query("SELECT * FROM packet_ledger WHERE expiryTimestamp > :now")
    fun getActivePackets(now: Long): Flow<List<PacketLedgerEntity>>

    @Query("DELETE FROM packet_ledger WHERE expiryTimestamp <= :now")
    suspend fun pruneExpiredPackets(now: Long)

    @Query(
        "SELECT COUNT(*) FROM packet_ledger " +
        "WHERE senderHash = :senderHash AND sequenceNumber = :seq"
    )
    suspend fun isPacketReplayed(senderHash: String, seq: Int): Int

    @Query("DELETE FROM packet_ledger")
    suspend fun deleteAllPackets()
}

@Dao
interface MessageDao {

    /** Returns the rowId of the inserted row — use as a stable ID for status updates. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message): Long

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getMessages(): Flow<List<Message>>

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Int, status: Int)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}

// ══════════════════════════════════════════════════════════════════════════════
// Database
// ══════════════════════════════════════════════════════════════════════════════

@Database(
    entities = [PacketLedgerEntity::class, Message::class],
    version = 5,
    exportSchema = false
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun packetLedgerDao(): PacketLedgerDao
    abstract fun messageDao(): MessageDao
}

// ══════════════════════════════════════════════════════════════════════════════
// Routing Engine — Replay Defense & Ledger Tracking
// ══════════════════════════════════════════════════════════════════════════════

/**
 * Stateless-ish validation layer. Does NOT manage its own database instance;
 * it receives the [PacketLedgerDao] from Hilt injection to avoid the
 * double-instantiation bug present in the original codebase.
 *
 * Responsibilities:
 * - Replay attack defense (deduplication by sender+sequence)
 * - TTL hop-count enforcement
 * - Ledger recording for Store-Carry-Forward relay
 */
class MeshRoutingEngine(private val packetLedgerDao: PacketLedgerDao) {

    /**
     * Validates and records an incoming packet.
     *
     * @return `true`  — packet is new, valid, and should be delivered + forwarded.
     * @return `false` — packet should be silently dropped (replay / expired TTL / duplicate).
     */
    suspend fun processIncomingPacket(
        senderHex: String,
        sequenceNumber: Int,
        ttl: Byte,
        payloadBlob: ByteArray
    ): Boolean {
        // 1. Replay attack defense — drop if we've seen this sender+seq before
        if (packetLedgerDao.isPacketReplayed(senderHex, sequenceNumber) > 0) {
            return false
        }

        // 2. TTL hop-count check
        if (ttl <= 0) return false

        // 3. Record in the distributed ledger (24-hour expiry)
        val packetId = "$senderHex-$sequenceNumber"
        val expiry = System.currentTimeMillis() + 24 * 60 * 60 * 1_000L
        val entity = PacketLedgerEntity(
            packetId = packetId,
            senderHash = senderHex,
            sequenceNumber = sequenceNumber,
            expiryTimestamp = expiry,
            payloadBlob = payloadBlob
        )
        val inserted = packetLedgerDao.insertPacket(entity)

        // -1L means IGNORE conflict fired — another coroutine raced us; treat as duplicate
        return inserted != -1L
    }

    /** Deletes expired ledger entries. Call periodically (e.g., every hour). */
    suspend fun pruneLedger() {
        packetLedgerDao.pruneExpiredPackets(System.currentTimeMillis())
    }
}
