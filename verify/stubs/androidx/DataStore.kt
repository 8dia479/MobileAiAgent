@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.datastore.preferences.core

interface Preferences {
    operator fun <T> get(key: Preferences.Key<T>): T?
    operator fun contains(key: Preferences.Key<*>): Boolean
    class Key<T>
}

abstract class MutablePreferences : Preferences {
    operator fun <T> set(key: Preferences.Key<T>, value: T) {}
    fun <T> remove(key: Preferences.Key<T>): T? = null
}

fun booleanPreferencesKey(name: String): Preferences.Key<Boolean> = Preferences.Key()
fun intPreferencesKey(name: String): Preferences.Key<Int> = Preferences.Key()
fun longPreferencesKey(name: String): Preferences.Key<Long> = Preferences.Key()
fun stringPreferencesKey(name: String): Preferences.Key<String> = Preferences.Key()
fun stringSetPreferencesKey(name: String): Preferences.Key<Set<String>> = Preferences.Key()

fun emptyPreferences(): Preferences = object : Preferences {
    override fun <T> get(key: Preferences.Key<T>): T? = null
    override fun contains(key: Preferences.Key<*>): Boolean = false
}

suspend fun androidx.datastore.core.DataStore<Preferences>.edit(
    transform: suspend (MutablePreferences) -> Unit,
): Preferences = throw UnsupportedOperationException("datastore stub")
