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
import android.widget.Toast
import com.example.evolvix.R
import com.example.evolvix.ui.viewmodel.AuthViewModel

/**
 * Set-new-password screen (Phase 9 / Polish Pass B2).
 *
 * Presents three password fields — current password, new password, and confirm new
 * password — before calling [AuthViewModel.changePassword]. The current password is
 * verified by [FakeAuthRepository] (and later `FirebaseAuthRepository`) so an
 * attacker with a stolen unlocked device cannot silently reset the credential.
 *
 * Validation rules (client-side, before the repository call):
 * - oldPassword: non-blank
 * - newPassword: non-blank, ≥ 6 characters
 * - confirmPassword: must equal newPassword
 * Wrong current password → repository returns failure → shown in Snackbar.
 *
 * (Pattern: **MVVM** — pure View. State flows down; events flow up.)
 *
 * @param viewModel      Shared [AuthViewModel] scoped to the auth nav graph.
 * @param onNavigateBack Pops back to the previous screen (e.g. SettingsScreen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetNewPasswordScreen(
    viewModel: AuthViewModel,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var oldPasswordVisible by remember { mutableStateOf(false) }
    var newPasswordVisible by remember { mutableStateOf(false) }
    var confirmVisible by remember { mutableStateOf(false) }
    var oldPasswordError by remember { mutableStateOf<String?>(null) }
    var newPasswordError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val emptyPassword = stringResource(R.string.error_password_empty)
    val tooShort = stringResource(R.string.error_password_too_short)
    val noMatch = stringResource(R.string.error_passwords_no_match)
    val passwordChangedMsg = stringResource(R.string.auth_password_changed)
    val labelOldPassword = stringResource(R.string.label_old_password)

    // Show a Toast and navigate back as soon as the password change succeeds.
    LaunchedEffect(uiState.resetEmailSent) {
        if (uiState.resetEmailSent) {
            Toast.makeText(context, passwordChangedMsg, Toast.LENGTH_SHORT).show()
            viewModel.clearResetSent()
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
                title = { Text(stringResource(R.string.screen_set_new_password_title)) },
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
            // ── Current (old) password field ──────────────────────────────────
            OutlinedTextField(
                value = oldPassword,
                onValueChange = { oldPassword = it; oldPasswordError = null },
                label = { Text(labelOldPassword) },
                singleLine = true,
                isError = oldPasswordError != null,
                supportingText = oldPasswordError?.let { { Text(it) } },
                visualTransformation = if (oldPasswordVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { oldPasswordVisible = !oldPasswordVisible }) {
                        Icon(
                            imageVector = if (oldPasswordVisible) Icons.Filled.VisibilityOff
                                          else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── New password field ────────────────────────────────────────────
            OutlinedTextField(
                value = newPassword,
                onValueChange = { newPassword = it; newPasswordError = null },
                label = { Text(stringResource(R.string.label_new_password)) },
                singleLine = true,
                isError = newPasswordError != null,
                supportingText = newPasswordError?.let { { Text(it) } },
                visualTransformation = if (newPasswordVisible) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { newPasswordVisible = !newPasswordVisible }) {
                        Icon(
                            imageVector = if (newPasswordVisible) Icons.Filled.VisibilityOff
                                          else Icons.Filled.Visibility,
                            contentDescription = stringResource(R.string.cd_toggle_password)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── Confirm new password field ────────────────────────────────────
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

            // ── Set Password button ───────────────────────────────────────────
            Button(
                onClick = {
                    var valid = true
                    if (oldPassword.isBlank()) { oldPasswordError = emptyPassword; valid = false }
                    if (newPassword.isBlank()) { newPasswordError = emptyPassword; valid = false }
                    else if (newPassword.length < 6) { newPasswordError = tooShort; valid = false }
                    if (confirmPassword != newPassword) { confirmError = noMatch; valid = false }
                    if (valid) viewModel.changePassword(oldPassword, newPassword)
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
                Text(stringResource(R.string.btn_set_password))
            }
        }
    }
}
