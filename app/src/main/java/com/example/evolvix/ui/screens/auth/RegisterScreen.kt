package com.example.evolvix.ui.screens.auth

import android.util.Patterns
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.evolvix.R
import com.example.evolvix.ui.viewmodel.AuthViewModel

/**
 * Registration screen for the Evolvix app (Phase 9).
 *
 * Collects the user's display name, email, and password. Validates:
 *  - Name is not blank
 *  - Email matches [Patterns.EMAIL_ADDRESS] (client-side format check before network round-trip)
 *  - Password is at least 6 characters
 *  - Confirm-password matches
 *
 * After successful registration, [onLoginSuccess] fires to trigger the nav-graph guard.
 * The display name is sent through [AuthViewModel.register] so it is persisted both on
 * the Firebase user profile (survives reinstalls) AND in the UID-scoped SharedPreferences
 * (immediately available to the Settings screen).
 *
 * (Pattern: **MVVM** — pure View, no business logic. State flows down from ViewModel;
 *  user events flow up through ViewModel functions.)
 *
 * @param viewModel           Shared [AuthViewModel] scoped to the auth nav graph.
 * @param onLoginSuccess      Called after successful registration (user is now logged in).
 * @param onNavigateBack      Pops back to [LoginScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    var displayName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val emptyEmail = stringResource(R.string.error_email_empty)
    val invalidEmail = stringResource(R.string.error_invalid_email)
    val emptyName = stringResource(R.string.error_display_name_empty)
    val emptyPassword = stringResource(R.string.error_password_empty)
    val tooShort = stringResource(R.string.error_password_too_short)
    val noMatch = stringResource(R.string.error_passwords_no_match)

    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) {
            onLoginSuccess()
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_register_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 24.dp)
                .imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // ── Name ──────────────────────────────────────────────────────────
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it; nameError = null },
                label = { Text(stringResource(R.string.label_your_name)) },
                singleLine = true,
                isError = nameError != null,
                supportingText = nameError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Email ─────────────────────────────────────────────────────────
            OutlinedTextField(
                value = email,
                onValueChange = { email = it; emailError = null },
                label = { Text(stringResource(R.string.label_email)) },
                singleLine = true,
                isError = emailError != null,
                supportingText = emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Password ──────────────────────────────────────────────────────
            OutlinedTextField(
                value = password,
                onValueChange = { password = it; passwordError = null },
                label = { Text(stringResource(R.string.label_password)) },
                singleLine = true,
                isError = passwordError != null,
                supportingText = passwordError?.let { { Text(it) } },
                visualTransformation = if (passwordVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) Icons.Filled.VisibilityOff
                                          else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Confirm password ──────────────────────────────────────────────
            OutlinedTextField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it; confirmError = null },
                label = { Text(stringResource(R.string.label_confirm_password)) },
                singleLine = true,
                isError = confirmError != null,
                supportingText = confirmError?.let { { Text(it) } },
                visualTransformation = if (confirmVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { confirmVisible = !confirmVisible }) {
                        Icon(
                            imageVector = if (confirmVisible) Icons.Filled.VisibilityOff
                                          else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Create Account button ─────────────────────────────────────────
            Button(
                onClick = {
                    var valid = true
                    if (displayName.isBlank()) { nameError = emptyName; valid = false }
                    if (email.isBlank()) { emailError = emptyEmail; valid = false }
                    else if (!Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
                        emailError = invalidEmail; valid = false
                    }
                    if (password.isBlank()) { passwordError = emptyPassword; valid = false }
                    else if (password.length < 6) { passwordError = tooShort; valid = false }
                    if (confirmPassword != password) { confirmError = noMatch; valid = false }
                    if (valid) viewModel.register(email.trim(), password, displayName.trim())
                },
                enabled = !uiState.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .height(18.dp)
                            .padding(end = 8.dp)
                    )
                }
                Text(stringResource(R.string.btn_create_account))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Back to Login link ────────────────────────────────────────────
            TextButton(onClick = onNavigateBack) {
                Text(
                    text = stringResource(R.string.auth_have_account) + " " +
                           stringResource(R.string.auth_sign_in_link),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
