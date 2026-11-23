package com.example.dropsecure.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers ORDER BY startedAtMs ASC")
    fun getAll(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE transferId = :id")
    fun get(id: String): TransferEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entity: TransferEntity)

    @Query("UPDATE transfers SET bytesDone = :bytes, updatedAtMs = :now WHERE transferId = :id")
    fun updateProgress(id: String, bytes: Long, now: Long)

    @Query("DELETE FROM transfers WHERE transferId = :id")
    fun delete(id: String)

    @Query("DELETE FROM transfers WHERE status IN ('COMPLETED','FAILED','CANCELLED','REJECTED')")
    fun clearFinished()

    /** A transfer still marked IN_PROGRESS at app start was killed with the process: it is resumable. */
    @Query("UPDATE transfers SET status = 'INTERRUPTED' WHERE status = 'IN_PROGRESS'")
    fun markStaleInterrupted()
}
