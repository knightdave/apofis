package xyz.rycerz.apofis.ui.connect

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisTheme

private fun Reason.text() = when (this) {
    Reason.MISSING_SCHEME -> R.string.connect_error_missing_scheme
    Reason.MALFORMED_URL -> R.string.connect_error_malformed_url
    Reason.URL_EXTRA_PARTS -> R.string.connect_error_url_extra_parts
    Reason.UNREACHABLE -> R.string.connect_error_unreachable
    Reason.NOT_HERMES -> R.string.connect_error_not_hermes
    Reason.KEY_REQUIRED -> R.string.connect_error_key_required
    Reason.KEY_INVALID -> R.string.connect_error_key_invalid
    Reason.KEY_REJECTED -> R.string.connect_error_key_rejected
    Reason.SAVE_FAILED -> R.string.connect_error_save_failed
}

/** First-run connect screen, and the Connection row of Settings when [onBack] is set. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectScreen(vm: ConnectViewModel, onBack: (() -> Unit)?) {
    val state = vm.state
    val saving = state == ConnectState.Saving
    val testing = state == ConnectState.Testing || saving
    val failed = state as? ConnectState.Failed
    val urlError = failed?.takeIf { it.field == Field.URL }?.reason?.text()?.let { stringResource(it) }
    val keyError = failed?.takeIf { it.field == Field.KEY }?.reason?.text()?.let { stringResource(it) }
    var keyVisible by rememberSaveable { mutableStateOf(false) }

    // The key can be shown here, so the window is secure while this screen is up: it never appears in Recents,
    // and screenshots and screen recordings of this screen are blocked.
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(painterResource(R.drawable.lucide_arrow_left), contentDescription = stringResource(R.string.back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.connect_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.connect_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = vm.url,
                onValueChange = vm::onUrlChange,
                label = { Text(stringResource(R.string.connect_url_label)) },
                placeholder = { Text(stringResource(R.string.connect_url_example)) },
                singleLine = true,
                enabled = !testing,
                isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (urlError != null) error(urlError) },
            )
            if (vm.httpWarning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.lucide_triangle_exclamation_point), contentDescription = null, tint = ApofisTheme.colors.warning, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(R.string.connect_http_warning),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            OutlinedSecureTextField(
                state = vm.apiKey,
                label = { Text(stringResource(R.string.connect_key_label)) },
                placeholder = if (vm.keepsCurrentKey) ({ Text(stringResource(R.string.connect_key_keep)) }) else null,
                enabled = !testing,
                isError = keyError != null,
                supportingText = keyError?.let { { Text(it) } },
                inputTransformation = { vm.onKeyEdited() },
                textObfuscationMode = if (keyVisible) TextObfuscationMode.Visible else TextObfuscationMode.Hidden,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                onKeyboardAction = { vm.connect() },
                trailingIcon = {
                    val description = stringResource(if (keyVisible) R.string.connect_key_hide_description else R.string.connect_key_show_description)
                    TextButton(
                        onClick = { keyVisible = !keyVisible },
                        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = description },
                    ) {
                        Text(stringResource(if (keyVisible) R.string.connect_key_hide else R.string.connect_key_show))
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { if (keyError != null) error(keyError) },
            )
            Button(
                onClick = { vm.connect() },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                if (testing) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(if (saving) R.string.connect_saving else R.string.connect_testing))
                } else {
                    Text(stringResource(R.string.connect_button))
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }

    if (state == ConnectState.ConfirmServerChange) {
        AlertDialog(
            onDismissRequest = vm::cancelServerChange,
            title = { Text(stringResource(R.string.server_change_title)) },
            text = { Text(stringResource(R.string.server_change_text)) },
            confirmButton = { TextButton(onClick = { vm.confirmServerChange() }) { Text(stringResource(R.string.server_change_confirm)) } },
            dismissButton = { TextButton(onClick = vm::cancelServerChange) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
