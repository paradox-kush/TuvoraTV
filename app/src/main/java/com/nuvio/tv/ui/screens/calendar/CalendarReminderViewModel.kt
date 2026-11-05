package com.nuvio.tv.ui.screens.calendar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.core.calendar.CalendarCornerMessage
import com.nuvio.tv.core.calendar.CalendarReminderCard
import com.nuvio.tv.core.calendar.CalendarReminderCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Duration
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Home's reminder card and the app-wide corner note, over the singleton [CalendarReminderCenter]. */
@HiltViewModel
class CalendarReminderViewModel @Inject constructor(
    private val center: CalendarReminderCenter
) : ViewModel() {
    val card: StateFlow<CalendarReminderCard?> = center.card
    val corner: StateFlow<CalendarCornerMessage?> = center.corner

    fun onHomeResumed() {
        viewModelScope.launch { center.onHomeResumed() }
    }

    fun dismissCard() = center.dismissCard()

    fun dismissCorner(id: Long) = center.dismissCorner(id)

    /** One foreground check; called from a `repeatOnLifecycle(RESUMED)` loop, which sleeps the returned delay. */
    suspend fun checkCorner(): Duration = center.checkCorner()
}
