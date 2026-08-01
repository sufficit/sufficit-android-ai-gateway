package com.sufficit.ai.gateway.ledger

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "interaction_turns")
data class InteractionTurnEntity(
    @PrimaryKey val turnId: String,
    val inputMode: String,
    val awakened: Boolean,
    val wakeWord: String?,
    val textHash: String,
    val state: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "interaction_sources",
    primaryKeys = ["turnId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = InteractionTurnEntity::class,
            parentColumns = ["turnId"],
            childColumns = ["turnId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("turnId")]
)
data class InteractionSourceEntity(
    val turnId: String,
    val messageId: Long,
    val position: Int,
    val textHash: String
)

@Entity(
    tableName = "remote_deliveries",
    indices = [Index("turnId"), Index(value = ["turnId", "attempt"], unique = true)]
)
data class RemoteDeliveryEntity(
    @PrimaryKey val deliveryId: String,
    val turnId: String,
    val transport: String,
    val attempt: Int,
    val state: String,
    val receipt: String?,
    val error: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "client_action_calls",
    indices = [Index("turnId"), Index("state")]
)
data class ClientActionCallEntity(
    @PrimaryKey val callId: String,
    val turnId: String?,
    val tool: String,
    val argumentsHash: String,
    val state: String,
    val summary: String,
    val retryable: Boolean,
    val error: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long
)

@Entity(
    tableName = "interaction_events",
    indices = [Index("turnId"), Index("callId"), Index("atEpochMs")]
)
data class InteractionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val turnId: String?,
    val callId: String?,
    val category: String,
    val state: String,
    val summary: String,
    val detailsJson: String?,
    val atEpochMs: Long
)
