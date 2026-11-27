@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.room

@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Entity(
    val tableName: String = "",
    val indices: Array<Index> = [],
    val primaryKeys: Array<String> = [],
    val foreignKeys: Array<ForeignKey> = [],
)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) @Retention(AnnotationRetention.BINARY)
annotation class PrimaryKey(val autoGenerate: Boolean = false)
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Dao
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class Database(val entities: Array<kotlin.reflect.KClass<*>> = [], val version: Int = 1, val exportSchema: Boolean = true)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Insert(val onConflict: Int = 1)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Query(val value: String)
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Update
@Target(AnnotationTarget.FUNCTION) @Retention(AnnotationRetention.BINARY)
annotation class Delete
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class TypeConverter
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) @Retention(AnnotationRetention.BINARY)
annotation class ColumnInfo(val name: String = "")
@Target(AnnotationTarget.CLASS) @Retention(AnnotationRetention.BINARY)
annotation class TypeConverters(vararg val value: kotlin.reflect.KClass<*>)
@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY) @Retention(AnnotationRetention.BINARY)
annotation class Embedded

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Index(val value: String = "", val unique: Boolean = false)

@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class ForeignKey(val entity: kotlin.reflect.KClass<*> = Unit::class, val parentColumns: Array<String> = [], val childColumns: Array<String> = [], val onDelete: Int = 1)

object OnConflictStrategy {
    const val REPLACE = 1
    const val IGNORE = 2
    const val ABORT = 3
}

abstract class RoomDatabase {
    abstract fun clearAllTables()
    open fun close() {}
    open fun openHelper(): Any = Unit
    companion object
}

object Room {
    fun databaseBuilder(context: android.content.Context, klass: Class<*>, name: String?): Builder = Builder()
    class Builder {
        fun addMigrations(vararg migrations: Migration): Builder = this
        fun fallbackToDestructiveMigration(): Builder = this
        fun fallbackToDestructiveMigrationOnDowngrade(): Builder = this
        fun setJournalMode(mode: Any?): Builder = this
        fun allowMainThreadQueries(): Builder = this
        fun <T> build(): T = throw UnsupportedOperationException("room stub")
    }
}

abstract class Migration(val startVersion: Int, val endVersion: Int) {
    abstract fun migrate(database: SupportSQLiteDatabase)
}

interface SupportSQLiteDatabase {
    fun execSQL(sql: String)
    fun query(query: String): Any
}
