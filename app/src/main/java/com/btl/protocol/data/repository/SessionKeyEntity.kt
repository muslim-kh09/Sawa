package com.btl.protocol.data.repository

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "session_keys")
data class SessionKeyEntity(
    @PrimaryKey val remoteFingerprint: String,
    val symmetricKeyEncrypted: ByteArray
) {
    override fun equals(other: Any?): Boolean =
        other is SessionKeyEntity && remoteFingerprint == other.remoteFingerprint
    override fun hashCode(): Int = remoteFingerprint.hashCode()
}
