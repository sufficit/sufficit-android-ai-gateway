package com.sufficit.ai.gateway.ledger

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface InteractionLedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertTurn(turn: InteractionTurnEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertSources(sources: List<InteractionSourceEntity>)

    @Query("SELECT * FROM interaction_turns WHERE turnId = :turnId LIMIT 1")
    fun findTurn(turnId: String): InteractionTurnEntity?

    @Query("SELECT * FROM interaction_turns WHERE state NOT IN ('completed','failed','canceled','interrupted')")
    fun openTurns(): List<InteractionTurnEntity>

    @Query("UPDATE interaction_turns SET state = :state, updatedAtEpochMs = :atEpochMs WHERE turnId = :turnId")
    fun updateTurnState(turnId: String, state: String, atEpochMs: Long): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertDelivery(delivery: RemoteDeliveryEntity)

    @Query("SELECT * FROM remote_deliveries WHERE deliveryId = :deliveryId LIMIT 1")
    fun findDelivery(deliveryId: String): RemoteDeliveryEntity?

    @Query("SELECT COALESCE(MAX(attempt), 0) FROM remote_deliveries WHERE turnId = :turnId")
    fun maxDeliveryAttempt(turnId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertAction(action: ClientActionCallEntity): Long

    @Query("SELECT * FROM client_action_calls WHERE callId = :callId LIMIT 1")
    fun findAction(callId: String): ClientActionCallEntity?

    @Query("SELECT * FROM client_action_calls WHERE state NOT IN ('succeeded','unverified','failed','timed_out','canceled','denied')")
    fun openActions(): List<ClientActionCallEntity>

    @Query("UPDATE client_action_calls SET state = :state, summary = :summary, retryable = :retryable, error = :error, updatedAtEpochMs = :atEpochMs WHERE callId = :callId")
    fun updateAction(
        callId: String,
        state: String,
        summary: String,
        retryable: Boolean,
        error: String?,
        atEpochMs: Long
    ): Int

    @Insert
    fun insertEvent(event: InteractionEventEntity): Long

    @Query("SELECT * FROM interaction_events ORDER BY atEpochMs DESC, id DESC LIMIT :limit")
    fun recentEvents(limit: Int): List<InteractionEventEntity>

    @Query("DELETE FROM interaction_events WHERE atEpochMs < :cutoffEpochMs")
    fun deleteEventsOlderThan(cutoffEpochMs: Long): Int

    @Query("DELETE FROM interaction_events WHERE id NOT IN (SELECT id FROM interaction_events ORDER BY atEpochMs DESC, id DESC LIMIT :keep)")
    fun trimEventsToCount(keep: Int): Int

    @Transaction
    fun createTurn(turn: InteractionTurnEntity, sources: List<InteractionSourceEntity>): Boolean {
        val inserted = insertTurn(turn) != -1L
        if (inserted && sources.isNotEmpty()) insertSources(sources)
        return inserted
    }
}
