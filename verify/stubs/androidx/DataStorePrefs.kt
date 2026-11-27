@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.datastore.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences

class PreferenceDataStoreDelegate {
    operator fun getValue(thisRef: Context, property: kotlin.reflect.KProperty<*>): DataStore<Preferences> =
        throw UnsupportedOperationException("stub")
}

fun preferencesDataStore(name: String): PreferenceDataStoreDelegate = PreferenceDataStoreDelegate()
