package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.data.local.AppDatabase
import com.example.evolvix.domain.auth.AuthRepository
import com.example.evolvix.domain.sync.SyncController

/**
 * Factory for [AuthViewModel].
 *
 * A custom factory is required because [AuthViewModel] takes multiple constructor
 * parameters — Jetpack's default [ViewModelProvider] cannot inject them automatically
 * without a DI framework.
 *
 * Phase 10 passes [FirebaseAuthRepository], [SyncController], [AppDatabase], and the
 * activity-scoped [SettingsViewModel] here so [AuthViewModel] can:
 * - Pull the user's Firestore data into Room on login.
 * - Clear all Room tables on logout (data isolation between accounts).
 * - Reload the UID-scoped display name after each login/logout transition.
 *
 * Pattern: **Factory Method + Dependency Inversion**.
 *
 * @param repository      Production [AuthRepository] implementation.
 * @param syncController  Mediator for Room ↔ Firestore bidirectional sync.
 * @param database        Local Room database; used to call [AppDatabase.clearAllTables].
 * @param settingsViewModel Activity-scoped settings VM; display name is reloaded after
 *                          each auth transition.
 */
class AuthViewModelFactory(
    private val repository: AuthRepository,
    private val syncController: SyncController,
    private val database: AppDatabase,
    private val settingsViewModel: SettingsViewModel
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AuthViewModel(repository, syncController, database, settingsViewModel) as T
    }
}
