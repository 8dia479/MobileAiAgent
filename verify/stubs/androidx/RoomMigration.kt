@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.room.migration
abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase)
}
