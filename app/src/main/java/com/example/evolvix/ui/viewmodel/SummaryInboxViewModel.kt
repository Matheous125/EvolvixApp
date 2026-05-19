package com.example.evolvix.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.data.local.DailySummaryDao
import com.example.evolvix.data.model.DailySummaryEntity
import com.example.evolvix.notifications.SummaryPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backing ViewModel for the daily-summary inbox screen (Phase 7.2 v2).
 *
 * Surfaces the reactive list of [DailySummaryEntity] rows and an `unreadCount` flow
 * used by the bell-icon badge on the main screen. Mutations are simple delegates to
 * the DAO so the UI never touches Room directly (Observer pattern via StateFlow).
 *
 * Opening the inbox is treated as user engagement, so the constructor also clears
 * the dismiss-streak counter — this is the "re-engagement reset" rule (see
 * [SummaryPreferences.resetDismissStreak]).
 */
class SummaryInboxViewModel(
    application: Application,
    private val dao: DailySummaryDao
) : AndroidViewModel(application) {

    init {
        // The user opened the inbox → they care → reset the dismissal counter.
        SummaryPreferences.resetDismissStreak(application)
    }

    val summaries: StateFlow<List<DailySummaryEntity>> = dao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val unreadCount: StateFlow<Int> = dao.getUnreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun markRead(id: Int) {
        viewModelScope.launch {
            dao.markRead(id)
            SummaryPreferences.setLastReadId(getApplication(), id)
        }
    }

    fun markAllRead() {
        viewModelScope.launch { dao.markAllRead() }
    }
}

/**
 * Factory for [SummaryInboxViewModel] so the `AppDatabase` singleton is injected at
 * the construction site, mirroring the existing [HabitViewModelFactory] pattern.
 */
class SummaryInboxViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SummaryInboxViewModel::class.java))
        val dao = AppDatabase.getDatabase(application).dailySummaryDao()
        @Suppress("UNCHECKED_CAST")
        return SummaryInboxViewModel(application, dao) as T
    }
}
