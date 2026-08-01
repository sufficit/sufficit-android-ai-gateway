package com.sufficit.ai.gateway.ledger

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        InteractionTurnEntity::class,
        InteractionSourceEntity::class,
        RemoteDeliveryEntity::class,
        ClientActionCallEntity::class,
        InteractionEventEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class InteractionLedgerDatabase : RoomDatabase() {
    abstract fun ledgerDao(): InteractionLedgerDao

    companion object {
        @Volatile private var instance: InteractionLedgerDatabase? = null

        fun get(context: Context): InteractionLedgerDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                InteractionLedgerDatabase::class.java,
                "interaction-ledger.db"
            ).build().also { instance = it }
        }
    }
}
