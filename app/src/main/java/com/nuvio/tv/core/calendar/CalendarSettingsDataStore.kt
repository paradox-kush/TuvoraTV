// Adapted from the Arc fork (ysosrs123/NuvioTV-Fork, GPL-3.0).
package com.nuvio.tv.core.calendar

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ProfileDataStoreFactory
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

enum class CalendarViewMode { LIST, MONTH }

data class CalendarSettings(
    val profileId: Int? = null,
    val windowDays: Int = CALENDAR_DEFAULT_WINDOW_DAYS,
    val zoneOverride: String? = null,
    val cornerMessage: Boolean = true,
    val comingUpRow: Boolean = false,
    val hiddenTitleKeys: Set<String> = emptySet(),
    val bellOn: Set<String> = emptySet(),
    val viewMode: CalendarViewMode = CalendarViewMode.LIST,
    /** Shows | Movies | Sports, multi-select; all on by default. */
    val filters: Set<CalendarFilter> = CALENDAR_ALL_FILTERS
)

internal object CalendarSettingsKeys {
    val windowDays = intPreferencesKey("calendar_window_days")
    val zoneOverride = stringPreferencesKey("calendar_zone_override")
    val cornerMessage = booleanPreferencesKey("calendar_corner_message")
    val comingUpRow = booleanPreferencesKey("calendar_coming_up_row")
    val hiddenTitleKeys = stringSetPreferencesKey("calendar_hidden_titles")
    val bellOn = stringSetPreferencesKey("calendar_bell_on")
    val viewMode = stringPreferencesKey("calendar_view_mode")
    val filters = stringSetPreferencesKey("calendar_filters")
}

fun normalizeCalendarWindowDays(days: Int?): Int =
    days?.takeIf { it in CALENDAR_WINDOW_OPTIONS } ?: CALENDAR_DEFAULT_WINDOW_DAYS

fun normalizeCalendarZoneOverride(zone: String?): String? =
    zone?.trim()?.takeIf { it.isNotEmpty() && runCatching { ZoneId.of(it) }.isSuccess }

/** The zone every Calendar day and time is counted in: the override when valid, else the box's own. */
fun resolveCalendarZone(override: String?, systemDefault: ZoneId): ZoneId =
    normalizeCalendarZoneOverride(override)?.let(ZoneId::of) ?: systemDefault

fun CalendarSettings?.resolvedZone(systemDefault: ZoneId = ZoneId.systemDefault()): ZoneId =
    resolveCalendarZone(this?.zoneOverride, systemDefault)

internal fun Preferences.toCalendarSettings() = CalendarSettings(
    windowDays = normalizeCalendarWindowDays(this[CalendarSettingsKeys.windowDays]),
    zoneOverride = normalizeCalendarZoneOverride(this[CalendarSettingsKeys.zoneOverride]),
    cornerMessage = this[CalendarSettingsKeys.cornerMessage] ?: true,
    comingUpRow = this[CalendarSettingsKeys.comingUpRow] ?: false,
    hiddenTitleKeys = this[CalendarSettingsKeys.hiddenTitleKeys].orEmpty(),
    bellOn = this[CalendarSettingsKeys.bellOn].orEmpty(),
    viewMode = this[CalendarSettingsKeys.viewMode]
        ?.let { name -> CalendarViewMode.entries.firstOrNull { it.name == name } } ?: CalendarViewMode.LIST,
    filters = this[CalendarSettingsKeys.filters]
        ?.mapNotNullTo(linkedSetOf()) { name -> CalendarFilter.entries.firstOrNull { it.name == name } }
        ?.takeIf { it.isNotEmpty() } ?: CALENDAR_ALL_FILTERS
)

/** Turning a title off also removes keys that name the same title by another of its ids. */
internal fun Set<String>.withTitleKey(titleKey: String, present: Boolean): Set<String> {
    if (present) return this + titleKey
    val (type, ids) = parseCalendarTitleKey(titleKey) ?: return this - titleKey
    return filterNotTo(linkedSetOf()) { it == titleKey || calendarKeyMatches(it, type, ids) }
}

internal fun MutablePreferences.toggleInSet(key: Preferences.Key<Set<String>>, value: String, present: Boolean) {
    this[key] = this[key].orEmpty().withTitleKey(value, present)
}

/** Per-profile Calendar settings: bell set, hidden titles, window, zone override, corner note, Coming up row, view. */
@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(profileId: Int = profileManager.activeProfileId.value) = factory.get(profileId, FEATURE)

    /** Null until the active profile's settings have been read, so hidden titles and the zone are never skipped. */
    val settings: StateFlow<CalendarSettings?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { it.toCalendarSettings().copy(profileId = profileId) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun current(profileId: Int = profileManager.activeProfileId.value): CalendarSettings =
        store(profileId).data.first().toCalendarSettings().copy(profileId = profileId)

    suspend fun setWindowDays(days: Int) =
        store().edit { it[CalendarSettingsKeys.windowDays] = normalizeCalendarWindowDays(days) }

    /** Back to the box's zone is stored as blank. */
    suspend fun setZoneOverride(zone: String?) = store().edit { preferences ->
        preferences[CalendarSettingsKeys.zoneOverride] = normalizeCalendarZoneOverride(zone).orEmpty()
    }

    suspend fun setCornerMessage(enabled: Boolean) = store().edit { it[CalendarSettingsKeys.cornerMessage] = enabled }

    suspend fun setComingUpRow(enabled: Boolean) = store().edit { it[CalendarSettingsKeys.comingUpRow] = enabled }

    suspend fun setViewMode(mode: CalendarViewMode) = store().edit { it[CalendarSettingsKeys.viewMode] = mode.name }

    suspend fun setFilters(filters: Set<CalendarFilter>) =
        store().edit { it[CalendarSettingsKeys.filters] = filters.mapTo(linkedSetOf()) { f -> f.name } }

    suspend fun setTitleHidden(titleKey: String, hidden: Boolean) =
        store().edit { it.toggleInSet(CalendarSettingsKeys.hiddenTitleKeys, titleKey, hidden) }

    suspend fun unhideAll() = store().edit { it[CalendarSettingsKeys.hiddenTitleKeys] = emptySet() }

    suspend fun setBell(titleKey: String, on: Boolean) =
        store().edit { it.toggleInSet(CalendarSettingsKeys.bellOn, titleKey, on) }

    companion object {
        const val FEATURE = "calendar_settings"
    }
}
