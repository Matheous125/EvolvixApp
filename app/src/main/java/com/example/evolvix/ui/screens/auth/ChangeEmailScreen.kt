package com.example.evolvix.ui.screens.auth

import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.example.evolvix.R
import com.example.evolvix.ui.viewmodel.AuthViewModel

/**
 * Change-e-mail screen (Polish-Pass E2).
 *
 * Mirrors [SetNewPasswordScreen]'s three-field structure: current password, new
 * e-mail, confirm new e-mail. The current password is verified by the repository
 * (FakeAuthRepository in Phase 9, FirebaseAuthRepository in Phase 10) before the
 * address is swapped, so an attacker with an unlocked device cannot silently take
 * over the account.
 *
 * Validation rules (client-side, before the repository call):
 *  - currentPassword: non-blank
 *  - newEmail:        non-blank, matches `EMAIL_REGEX`
 *  - confirmEmail:    must equal newEmail (case-insensitive after trim)
 *  - Repository additionally rejects address-equals-current and address-already-taken.
 *
 * (Pattern: **MVVM** — pure View. State flows down; events flow up.)
 *
 * @param viewModel      Shared [AuthViewModel] scoped to the auth nav graph.
 * @param onNavigateBack Pops back to the previous screen (e.g. SettingsScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeEmailScreen(
    viewModel: AuthViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    var currentPassword by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var confirmEmail by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val emptyPassword = stringResource(R.string.error_password_empty)
    val emptyEmail = stringResource(R.string.error_email_empty)
    val invalidEmail = stringResource(R.string.error_invalid_email)
    val noMatch = stringResource(R.string.error_emails_no_match)
    val emailChangedMsg = stringResource(R.string.auth_email_changed)

    // Toast + back-nav once the change succeeds. clearEmailChanged() prevents the
    // effect from re-firing on a configuration change (Phase-9 MVVM pattern).
    LaunchedEffect(uiState.emailChanged) {
        if (uiState.emailChanged) {
            Toast.makeText(context, emailChangedMsg, Toast.LENGTH_SHORT).show()
            viewModel.clearEmailChanged()
            onNavigateBack()
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
                title = { Text(stringResource(R.string.screen_change_email_title)) },
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
            // ── Current password field ────────────────────────────────────────
            OutlinedTextField(
                value = currentPassword,
                onValueChange = { currentPassword = it; passwordError = null },
                label = { Text(stringResource(R.string.label_old_password)) },
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

            // ── New e-mail field ──────────────────────────────────────────────
            OutlinedTextField(
                value = newEmail,
                onValueChange = { newEmail = it; emailError = null },
                label = { Text(stringResource(R.string.label_new_email)) },
                singleLine = true,
                isError = emailError != null,
                supportingText = emailError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Confirm new e-mail field ──────────────────────────────────────
            OutlinedTextField(
                value = confirmEmail,
                onValueChange = { confirmEmail = it; confirmError = null },
                label = { Text(stringResource(R.string.label_confirm_email)) },
                singleLine = true,
                isError = confirmError != null,
                supportingText = confirmError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(24.dp))

            // ── Change e-mail button ──────────────────────────────────────────
            Button(
                onClick = {
                    var valid = true
                    if (currentPassword.isBlank()) {
                        passwordError = emptyPassword; valid = false
                    }
                    val trimmedNew = newEmail.trim()
                    if (trimmedNew.isBlank()) {
                        emailError = emptyEmail; valid = false
                    } else if (!EMAIL_REGEX.matches(trimmedNew)) {
                        emailError = invalidEmail; valid = false
                    }
                    if (!trimmedNew.equals(confirmEmail.trim(), ignoreCase = true)) {
                        confirmError = noMatch; valid = false
                    }
                    if (valid) viewModel.changeEmail(currentPassword, trimmedNew)
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
                Text(stringResource(R.string.btn_change_email))
            }
        }
    }
}

/**
 * Same subset of RFC-5322 that the login/register/reset screens use. Kept as a
 * private top-level constant so client-side validation matches what
 * [com.example.evolvix.domain.auth.FakeAuthRepository] enforces server-side.
 */
private val EMAIL_REGEX = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")
