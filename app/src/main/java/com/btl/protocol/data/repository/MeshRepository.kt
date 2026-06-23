package com.btl.protocol.data.repository

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for all database operations.
 *
 * Injected via Hilt — eliminates the double Room instantiation that existed
 * between [MainActivity] and [MeshModule] in the previous codebase.
 * Both the Service and ViewModel go through this class exclusively.
 */
@Singleton
class MeshRepository @Inject constructor(
    private val messageDao: MessageDao,
    private val packetLedgerDao: PacketLedgerDao,
    private val sessionKeyDao: SessionKeyDao
) {
    // ──────────────────────────────────────────────────────────────────────────
    // Messages
    // ──────────────────────────────────────────────────────────────────────────

    /** Observable stream of all messages ordered by timestamp ascending. */
    fun observeMessages(): Flow<List<Message>> = messageDao.getMessages()

    /**
     * Inserts a message and returns its auto-generated database ID.
     * The ID is used by the transmission pipeline to update the delivery status.
     */
    suspend fun addMessage(message: Message): Long = messageDao.insertMessage(message)

    /**
     * Updates the status of a previously inserted message.
     *
     * @param id     The database row ID returned by [addMessage].
     * @param status One of [STATUS_PENDING], [STATUS_SENT], [STATUS_DELIVERED].
     */
    suspend fun updateStatus(id: Long, status: Int) {
        messageDao.updateStatus(id.toInt(), status)
    }

    suspend fun getAllMessageIds(): List<String> = messageDao.getAllMessageIds()
    
    suspend fun getMessageById(msgId: String): Message? = messageDao.getMessageById(msgId)

    // ──────────────────────────────────────────────────────────────────────────
    // Packet Ledger
    // ──────────────────────────────────────────────────────────────────────────

    /** Returns true if this senderHash + sequenceNumber combination is already recorded. */
    suspend fun isPacketSeen(senderHex: String, seqNum: Int): Boolean =
        packetLedgerDao.isPacketReplayed(senderHex, seqNum) > 0

    suspend fun recordPacket(entity: PacketLedgerEntity) {
        packetLedgerDao.insertPacket(entity)
    }

    /** Deletes all ledger entries older than the current time. */
    suspend fun pruneExpired() {
        packetLedgerDao.pruneExpiredPackets(System.currentTimeMillis())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Session Keys
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun getSessionKey(fingerprint: String): SessionKeyEntity? =
        sessionKeyDao.getSessionKey(fingerprint)

    suspend fun saveSessionKey(entity: SessionKeyEntity) {
        sessionKeyDao.insertSessionKey(entity)
    }

    /** PANIC MODE: Instantly purges all messages and packet history from the database. */
    suspend fun purgeDatabase() {
        messageDao.deleteAllMessages()
        packetLedgerDao.deleteAllPackets()
        sessionKeyDao.deleteAllSessionKeys()
    }
}
