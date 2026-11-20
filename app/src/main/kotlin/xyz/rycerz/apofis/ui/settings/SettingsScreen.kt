package xyz.rycerz.apofis.ui.settings

import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** Scheme, host, port and path only: anything else in a stored URL could be a secret. Empty when unparsable. */
internal fun displayBaseUrl(url: String): String =
    url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString().orEmpty()

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
    onLicenses: () -> Unit,
    onBack: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.settings_source_url)
    var linkFailed by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    ApofisIconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                        Icon(painterResource(R.drawable.lucide_arrow_left), contentDescription = null, Modifier.size(20.dp))
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
                InfoRow(R.drawable.lucide_server, stringResource(R.string.settings_server), displayBaseUrl(serverUrl), chevron = true, onClick = onConnection)
            }
            Section(R.string.settings_appearance) {
                Column(Modifier.selectableGroup()) {
                    listOf(
                        Triple(ThemeMode.SYSTEM, R.string.settings_theme_system, R.drawable.lucide_smartphone),
                        Triple(ThemeMode.LIGHT, R.string.settings_theme_light, R.drawable.lucide_sun),
                        Triple(ThemeMode.DARK, R.string.settings_theme_dark, R.drawable.lucide_moon),
                    ).forEach { (mode, label, icon) ->
                        RowLayout(
                            Modifier.selectable(themeMode == mode, role = Role.RadioButton, onClick = { onThemeMode(mode) }),
                            icon,
                            stringResource(label),
                        ) { RadioButton(selected = themeMode == mode, onClick = null) }
                    }
                }
                SwitchRow(R.drawable.lucide_palette, R.string.settings_dynamic_color, R.string.settings_dynamic_color_summary, dynamicColor, onDynamicColor)
                SwitchRow(R.drawable.lucide_contrast, R.string.settings_pure_black, R.string.settings_pure_black_summary, pureBlack, onPureBlack)
            }
            Section(R.string.settings_about) {
                InfoRow(R.drawable.lucide_info, stringResource(R.string.settings_version), versionName)
                InfoRow(R.drawable.lucide_scale, stringResource(R.string.settings_license), stringResource(R.string.settings_license_value))
                InfoRow(R.drawable.lucide_code, stringResource(R.string.settings_source), sourceUrl, chevron = true, onClick = {
                    linkFailed = runCatching { uriHandler.openUri(sourceUrl) }.isFailure
                })
                InfoRow(R.drawable.lucide_file_text, stringResource(R.string.settings_licenses), chevron = true, onClick = onLicenses)
                if (linkFailed) {
                    Text(
                        stringResource(R.string.settings_link_no_app),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .padding(start = 48.dp, end = 12.dp, bottom = 11.dp)
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
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
        color = ApofisTheme.colors.sectionTitle,
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
private fun InfoRow(icon: Int, title: String, value: String? = null, chevron: Boolean = false, onClick: (() -> Unit)? = null) =
    RowLayout(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier, icon, title, value) {
        if (chevron) {
            Icon(
                painterResource(R.drawable.lucide_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }

@Composable
private fun SwitchRow(icon: Int, title: Int, summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) = RowLayout(
    Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange),
    icon,
    stringResource(title),
    stringResource(summary),
) { Switch(checked = checked, onCheckedChange = null) }

/** 15 sp row, 20 dp icon in a 36 dp slot, 12 by 11 dp padding, at least 48 dp tall; [trailing] sits at the end. */
@Composable
private fun RowLayout(modifier: Modifier, icon: Int, title: String, value: String? = null, trailing: @Composable () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(36.dp)) {
            Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(20.dp))
        }
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
