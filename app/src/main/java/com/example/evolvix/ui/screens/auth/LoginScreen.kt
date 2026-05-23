package com.example.evolvix.ui.screens.auth

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
 * Login screen for the Evolvix app (Phase 9).
 *
 * Purely declarative: collects [AuthViewModel.uiState] and routes all user events
 * back through the ViewModel. No business logic lives here (Pattern: **MVVM**).
 *
 * When [AuthUiState.isAuthenticated] becomes true the [onLoginSuccess] callback fires
 * so the NavGraph can navigate to the main app graph.
 *
 * @param viewModel          Shared [AuthViewModel] scoped to the auth nav graph.
 * @param onLoginSuccess     Called after a successful login or register operation.
 * @param onNavigateToRegister  Navigates to [RegisterScreen].
 * @param onNavigateToResetPassword  Navigates to [ResetPasswordScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    viewModel: AuthViewModel,
    onLoginSuccess: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToResetPassword: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Local form state — pure UI concern, not business logic.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var passwordError by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }
    val emptyEmail = stringResource(R.string.error_email_empty)
    val emptyPassword = stringResource(R.string.error_password_empty)

    // Navigate away as soon as authentication succeeds.
    // (Pattern: Observer — LaunchedEffect reacts to state changes from the ViewModel)
    LaunchedEffect(uiState.isAuthenticated) {
        if (uiState.isAuthenticated) onLoginSuccess()
    }

    // Show ViewModel errors in a Snackbar, then clear them so they don't re-appear.
    LaunchedEffect(uiState.error) {
        uiState.error?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.screen_login_title)) })
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
            // ── Email field ───────────────────────────────────────────────────
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

            // ── Password field ────────────────────────────────────────────────
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

            // ── Forgot password link ──────────────────────────────────────────
            TextButton(
                onClick = onNavigateToResetPassword,
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(R.string.auth_forgot_password))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ── Sign In button ────────────────────────────────────────────────
            Button(
                onClick = {
                    // UI-level validation — keeps empty-field errors in the View layer
                    // so the ViewModel never sees trivially invalid input.
                    var valid = true
                    if (email.isBlank()) { emailError = emptyEmail; valid = false }
                    if (password.isBlank()) { passwordError = emptyPassword; valid = false }
                    if (valid) viewModel.login(email.trim(), password)
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
                Text(stringResource(R.string.btn_sign_in))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Register link ─────────────────────────────────────────────────
            TextButton(onClick = onNavigateToRegister) {
                Text(
                    text = stringResource(R.string.auth_no_account) + " " +
                           stringResource(R.string.auth_sign_up),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
