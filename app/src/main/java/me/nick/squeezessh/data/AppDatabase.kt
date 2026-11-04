package me.nick.squeezessh.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [HostEntity::class, IdentityEntity::class, KeyEntity::class, KnownHostEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun hosts(): HostDao
    abstract fun identities(): IdentityDao
    abstract fun keys(): KeyDao
    abstract fun knownHosts(): KnownHostDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "squeezessh.db").build()
    }
}
