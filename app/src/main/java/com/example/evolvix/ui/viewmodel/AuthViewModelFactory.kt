package com.example.evolvix.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.evolvix.domain.auth.AuthRepository

/**
 * Factory for [AuthViewModel].
 *
 * A custom factory is required because [AuthViewModel] takes [AuthRepository] as a
 * constructor parameter — Jetpack's default [ViewModelProvider] cannot inject it
 * automatically without a DI framework.
 *
 * Phase 10 passes `FirebaseAuthRepository` here instead of [FakeAuthRepository] — the
 * ViewModel itself remains untouched (Pattern: **Factory Method + Dependency Inversion**).
 *
 * @param repository The [AuthRepository] implementation to inject into [AuthViewModel].
 */
class AuthViewModelFactory(
    private val repository: AuthRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AuthViewModel::class.java)) {
            "Unknown ViewModel class: ${modelClass.name}"
        }
        return AuthViewModel(repository) as T
    }
}
