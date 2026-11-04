package me.nick.squeezessh.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY nickname COLLATE NOCASE")
    fun observeAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun get(id: String): HostEntity?

    @Upsert
    suspend fun upsert(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("UPDATE hosts SET lastConnectedAt = :at, connectCount = connectCount + 1 WHERE id = :id")
    suspend fun markConnected(id: String, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM hosts")
    suspend fun getAll(): List<HostEntity>

    @Upsert
    suspend fun upsertAll(hosts: List<HostEntity>)

    @Query("DELETE FROM hosts")
    suspend fun deleteAll()
}

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities ORDER BY nickname COLLATE NOCASE")
    fun observeAll(): Flow<List<IdentityEntity>>

    @Query("SELECT * FROM identities WHERE id = :id")
    suspend fun get(id: String): IdentityEntity?

    @Upsert
    suspend fun upsert(identity: IdentityEntity)

    @Delete
    suspend fun delete(identity: IdentityEntity)

    @Query("SELECT * FROM identities")
    suspend fun getAll(): List<IdentityEntity>

    @Query("DELETE FROM identities")
    suspend fun deleteAll()
}

@Dao
interface KeyDao {
    @Query("SELECT * FROM keys ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<KeyEntity>>

    @Query("SELECT * FROM keys WHERE id = :id")
    suspend fun get(id: String): KeyEntity?

    @Query("SELECT * FROM keys")
    suspend fun getAll(): List<KeyEntity>

    @Query("SELECT * FROM keys WHERE fingerprint = :fingerprint LIMIT 1")
    suspend fun findByFingerprint(fingerprint: String): KeyEntity?

    @Upsert
    suspend fun upsert(key: KeyEntity)

    @Delete
    suspend fun delete(key: KeyEntity)

    @Query("DELETE FROM keys")
    suspend fun deleteAll()
}

@Dao
interface KnownHostDao {
    @Query("SELECT * FROM known_hosts WHERE hostPort = :hostPort")
    suspend fun forHost(hostPort: String): List<KnownHostEntity>

    @Upsert
    suspend fun upsert(entry: KnownHostEntity)

    @Query("DELETE FROM known_hosts WHERE hostPort = :hostPort")
    suspend fun forget(hostPort: String)

    @Query("SELECT * FROM known_hosts")
    suspend fun getAll(): List<KnownHostEntity>

    /** How many distinct hosts have a saved key (a host with several key types counts once). */
    @Query("SELECT COUNT(DISTINCT hostPort) FROM known_hosts")
    fun observeHostCount(): Flow<Int>

    @Upsert
    suspend fun upsertAll(entries: List<KnownHostEntity>)

    @Query("DELETE FROM known_hosts")
    suspend fun deleteAll()
}

@Dao
interface ForwardDao {
    @Query("SELECT * FROM forwards WHERE hostId = :hostId ORDER BY type, bindPort")
    suspend fun forHost(hostId: String): List<ForwardEntity>

    @Query("SELECT * FROM forwards WHERE hostId = :hostId ORDER BY type, bindPort")
    fun observeForHost(hostId: String): Flow<List<ForwardEntity>>

    @Query("DELETE FROM forwards WHERE hostId = :hostId")
    suspend fun deleteForHost(hostId: String)

    @Upsert
    suspend fun upsertAll(forwards: List<ForwardEntity>)

    @Query("SELECT * FROM forwards")
    suspend fun getAll(): List<ForwardEntity>

    @Query("DELETE FROM forwards")
    suspend fun deleteAll()
}

@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun get(id: String): SnippetEntity?

    @Upsert
    suspend fun upsert(snippet: SnippetEntity)

    @Delete
    suspend fun delete(snippet: SnippetEntity)

    @Query("SELECT * FROM snippets")
    suspend fun getAll(): List<SnippetEntity>

    @Upsert
    suspend fun upsertAll(snippets: List<SnippetEntity>)

    @Query("DELETE FROM snippets")
    suspend fun deleteAll()
}
