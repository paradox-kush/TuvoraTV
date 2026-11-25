package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.domain.model.CwOrderDefaultPolicy
import com.nuvio.tv.domain.model.CwOrderDefaultPolicy.Cohort
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.cwOrderCohortDataStore by preferencesDataStore(
    name = "cw_order_cohort",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { androidx.datastore.preferences.core.emptyPreferences() }
)

/** Device-level, write-once record of whether this install predates the resume-first default. */
@Singleton
class CwOrderCohortStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cohortKey = stringPreferencesKey("cohort")

    val cohort: Flow<Cohort?> = context.cwOrderCohortDataStore.data.map { prefs ->
        prefs[cohortKey]?.let { name -> Cohort.entries.firstOrNull { it.name == name } }
    }

    /** Persists the cohort the first time only; later calls keep the original decision. */
    suspend fun decideOnce(layoutAlreadyChosen: Boolean, hasStoredOrder: Boolean) {
        if (cohort.first() != null) return
        val decided = CwOrderDefaultPolicy.cohortFor(layoutAlreadyChosen, hasStoredOrder)
        context.cwOrderCohortDataStore.edit { prefs ->
            if (prefs[cohortKey] == null) prefs[cohortKey] = decided.name
        }
    }
}
