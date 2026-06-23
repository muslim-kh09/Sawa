package com.btl.protocol.data.repository

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.btl.protocol.domain.model.BtlPacket
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "packet_ledger")
data class PacketLedgerEntity(
    @PrimaryKey val packetId: String,
    val senderHash: String,
    val sequenceNumber: Int,
    val expiryTimestamp: Long,
    val payloadBlob: ByteArray
)

@Dao
interface PacketLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPacket(packet: PacketLedgerEntity): Long

    @Query("SELECT * FROM packet_ledger WHERE expiryTimestamp > :currentTime")
    fun getActivePackets(currentTime: Long): Flow<List<PacketLedgerEntity>>

    @Query("DELETE FROM packet_ledger WHERE expiryTimestamp <= :currentTime")
    suspend fun pruneExpiredPackets(currentTime: Long)
    
    @Query("SELECT COUNT(*) FROM packet_ledger WHERE senderHash = :senderHash AND sequenceNumber = :sequenceNumber")
    suspend fun isPacketReplayed(senderHash: String, sequenceNumber: Int): Int
}

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val isMe: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: Message)

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun getMessages(): Flow<List<Message>>
}

@Database(entities = [PacketLedgerEntity::class, Message::class], version = 2, exportSchema = false)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun packetLedgerDao(): PacketLedgerDao
    abstract fun messageDao(): MessageDao
}

class MeshRoutingEngine(context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        MeshDatabase::class.java,
        "btl_mesh_ledger.db"
    ).build()

    private val dao = db.packetLedgerDao()

    suspend fun processIncomingPacket(packet: BtlPacket): Boolean {
        val senderHex = packet.senderHash.joinToString("") { "%02x".format(it) }
        
        // 1. Strict defense against Replay Attacks
        val replayCount = dao.isPacketReplayed(senderHex, packet.sequenceNumber)
        if (replayCount > 0) {
            return false // Drop packet, already seen
        }

        // 2. TTL Routing Check
        if (packet.ttl <= 0) {
            return false // Drop packet, hop limit reached
        }

        // 3. Decentralized Ledger Tracking
        val packetId = "$senderHex-${packet.sequenceNumber}"
        val expiryTime = System.currentTimeMillis() + (24 * 60 * 60 * 1000) // 24 hours expiry
        
        val entity = PacketLedgerEntity(
            packetId = packetId,
            senderHash = senderHex,
            sequenceNumber = packet.sequenceNumber,
            expiryTimestamp = expiryTime,
            payloadBlob = packet.serialize(ByteArray(32)) // Placeholder HMAC Key
        )
        
        val inserted = dao.insertPacket(entity)
        if (inserted == -1L) return false

        // 4. Store-Carry-Forward Algorithm Trigger
        packet.ttl = (packet.ttl - 1).toByte()
        forwardPacketToAdjacentNodes(packet)
        
        return true
    }

    private fun forwardPacketToAdjacentNodes(packet: BtlPacket) {
        // Broadcast payload matrix offline via Wi-Fi direct queues / BLE peripheral traits
    }
    
    suspend fun pruneLedger() {
        dao.pruneExpiredPackets(System.currentTimeMillis())
    }
}
