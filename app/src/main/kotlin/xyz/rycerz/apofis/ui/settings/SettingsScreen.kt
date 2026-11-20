package xyz.rycerz.apofis.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** Grouped cards of UI map section 8. Stateless: the caller owns the values. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    pureBlack: Boolean,
    versionName: String,
    onConnection: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onPureBlack: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.settings_source_url)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Section(R.string.settings_connection) {
                InfoRow(stringResource(R.string.settings_server), value = serverUrl, chevron = true, onClick = onConnection)
            }
            Section(R.string.settings_appearance) {
                Column(Modifier.selectableGroup()) {
                    listOf(
                        ThemeMode.SYSTEM to R.string.settings_theme_system,
                        ThemeMode.LIGHT to R.string.settings_theme_light,
                        ThemeMode.DARK to R.string.settings_theme_dark,
                    ).forEach { (mode, label) ->
                        RowLayout(
                            Modifier.selectable(themeMode == mode, role = Role.RadioButton, onClick = { onThemeMode(mode) }),
                            stringResource(label),
                        ) { RadioButton(selected = themeMode == mode, onClick = null) }
                    }
                }
                SwitchRow(R.string.settings_dynamic_color, R.string.settings_dynamic_color_summary, dynamicColor, onDynamicColor)
                SwitchRow(R.string.settings_pure_black, R.string.settings_pure_black_summary, pureBlack, onPureBlack)
            }
            Section(R.string.settings_about) {
                InfoRow(stringResource(R.string.settings_version), value = versionName)
                InfoRow(stringResource(R.string.settings_license), value = stringResource(R.string.settings_license_value))
                InfoRow(stringResource(R.string.settings_source), value = sourceUrl, chevron = true, onClick = { runCatching { uriHandler.openUri(sourceUrl) } }) // no browser: nothing to do
            }
            Spacer(Modifier.size(16.dp))
        }
    }
}

@Composable
private fun Section(title: Int, content: @Composable ColumnScope.() -> Unit) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 8.dp).semantics { heading() },
    )
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = ApofisTheme.colors.cardFill,
        border = BorderStroke(0.6.dp, ApofisTheme.colors.hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(vertical = 4.dp), content = content)
    }
}

@Composable
private fun InfoRow(title: String, value: String? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null) =
    RowLayout(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, title, value) {
        if (chevron) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }

@Composable
private fun SwitchRow(title: Int, summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) = RowLayout(
    Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    stringResource(title),
    stringResource(summary),
) { Switch(checked = checked, onCheckedChange = null) }

/** 15 sp row, 12 by 11 dp padding, at least 48 dp tall; [trailing] sits at the end. */
@Composable
private fun RowLayout(modifier: Modifier, title: String, value: String? = null, trailing: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (value != null) {
                Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(12.dp))
        trailing()
    }
}
