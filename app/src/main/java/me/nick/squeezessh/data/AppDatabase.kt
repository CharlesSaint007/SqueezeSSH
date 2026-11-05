package me.nick.squeezessh.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        HostEntity::class,
        IdentityEntity::class,
        KeyEntity::class,
        KnownHostEntity::class,
        ForwardEntity::class,
        SnippetEntity::class,
    ],
    version = 4,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun identities(): IdentityDao
    abstract fun keys(): KeyDao
    abstract fun knownHosts(): KnownHostDao
    abstract fun forwards(): ForwardDao
    abstract fun snippets(): SnippetDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "squeezessh.db").build()
    }
}
