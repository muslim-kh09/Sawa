package com.btl.protocol.di

import android.content.Context
import androidx.room.Room
import com.btl.protocol.data.crypto.AndroidKeyStoreWrapper
import com.btl.protocol.data.repository.MeshDatabase
import com.btl.protocol.data.repository.MeshRoutingEngine
import com.btl.protocol.data.repository.PacketLedgerDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    @Singleton
    fun provideAndroidKeyStoreWrapper(): AndroidKeyStoreWrapper {
        return AndroidKeyStoreWrapper()
    }

    @Provides
    @Singleton
    fun provideMeshDatabase(@ApplicationContext context: Context): MeshDatabase {
        return Room.databaseBuilder(
            context,
            MeshDatabase::class.java,
            "btl_mesh_ledger.db"
        ).fallbackToDestructiveMigration().build()
    }

    @Provides
    @Singleton
    fun providePacketLedgerDao(database: MeshDatabase): PacketLedgerDao {
        return database.packetLedgerDao()
    }

    @Provides
    @Singleton
    fun provideMeshRoutingEngine(@ApplicationContext context: Context): MeshRoutingEngine {
        return MeshRoutingEngine(context)
    }
}
