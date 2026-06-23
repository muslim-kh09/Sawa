package com.btl.protocol.di

import android.content.Context
import androidx.room.Room
import com.btl.protocol.data.repository.MeshDatabase
import com.btl.protocol.data.repository.MeshRepository
import com.btl.protocol.data.repository.MessageDao
import com.btl.protocol.data.repository.MeshRoutingEngine
import com.btl.protocol.data.repository.PacketLedgerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt DI module for the Sawa mesh layer.
 *
 * ## Changes from the original
 * - **One database instance**: The original codebase created two separate Room
 *   instances (one in [MainActivity], one here). This module is now the ONLY
 *   place where [MeshDatabase] is instantiated.
 * - **DAO injection**: [MessageDao] and [PacketLedgerDao] are provided separately
 *   so [MeshRepository] and [MeshRoutingEngine] each receive only what they need.
 * - **[MeshRoutingEngine]**: Now takes an injected [PacketLedgerDao] instead of
 *   building its own database — eliminating the circular DB ownership bug.
 * - **[AndroidKeyStoreWrapper]**: Removed from the module — it now uses
 *   `@Inject constructor()` directly with `@Singleton`, so Hilt handles it.
 */
@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    @Singleton
    fun provideMeshDatabase(@ApplicationContext context: Context): MeshDatabase =
        Room.databaseBuilder(
            context,
            MeshDatabase::class.java,
            "btl_mesh_ledger.db"
        )
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun providePacketLedgerDao(db: MeshDatabase): PacketLedgerDao =
        db.packetLedgerDao()

    @Provides
    @Singleton
    fun provideMessageDao(db: MeshDatabase): MessageDao =
        db.messageDao()

    @Provides
    @Singleton
    fun provideMeshRepository(
        messageDao: MessageDao,
        packetLedgerDao: PacketLedgerDao
    ): MeshRepository = MeshRepository(messageDao, packetLedgerDao)

    @Provides
    @Singleton
    fun provideMeshRoutingEngine(packetLedgerDao: PacketLedgerDao): MeshRoutingEngine =
        MeshRoutingEngine(packetLedgerDao)
}
