package xyz.rycerz.apofis.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.settings.CHAT_FONT_SCALE_MAX
import xyz.rycerz.apofis.settings.CHAT_FONT_SCALE_MIN
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.settings.chatFontScaleStep
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.PillSwitch
import xyz.rycerz.apofis.ui.theme.SheetRow
import xyz.rycerz.apofis.ui.theme.wash
import kotlin.math.roundToInt

/** Scheme, host, port and path only: anything else in a stored URL could be a secret. Empty when unparsable. */
internal fun displayBaseUrl(url: String): String =
    url.toHttpUrlOrNull()?.newBuilder()?.username("")?.password("")?.query(null)?.fragment(null)?.build()?.toString().orEmpty()

/**
 * Grouped cards of UI map section 8. Stateless: the caller owns the values, and each shows what is stored. One feedback
 * event per gesture: navigation rows and switch rows give a selection, the theme choice only its sheet row's, the slider
 * one per step it moves; nothing else does. [themeSheetOpen] and [scrollState] set the initial state (goldens).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    serverUrl: String,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    pureBlack: Boolean,
    blur: Boolean,
    chatFontScale: Float,
    enterToSend: Boolean,
    haptics: Boolean,
    versionName: String,
    notifications: @Composable () -> Unit,
    onConnection: () -> Unit,
    onThemeMode: (ThemeMode) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onPureBlack: (Boolean) -> Unit,
    onBlur: (Boolean) -> Unit,
    onChatFontScale: (Float) -> Unit,
    onEnterToSend: (Boolean) -> Unit,
    onHaptics: (Boolean) -> Unit,
    onLicenses: () -> Unit,
    onBack: () -> Unit,
    themeSheetOpen: Boolean = false,
    scrollState: ScrollState = rememberScrollState(),
) {
    val uriHandler = LocalUriHandler.current
    val sourceUrl = stringResource(R.string.settings_source_url)
    var linkFailed by remember { mutableStateOf(false) }
    var themeSheet by rememberSaveable { mutableStateOf(themeSheetOpen) } // it always shows the stored mode
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp),
        ) {
            Section(R.string.settings_connection) {
                InfoRow(R.drawable.lucide_server, stringResource(R.string.settings_server), displayBaseUrl(serverUrl), onClick = onConnection)
            }
            Section(R.string.settings_appearance) {
                InfoRow(R.drawable.lucide_contrast, stringResource(R.string.settings_theme), stringResource(themeLabel(themeMode)), onClick = { themeSheet = true })
                SwitchRow(R.drawable.lucide_palette, R.string.settings_dynamic_color, R.string.settings_dynamic_color_summary, dynamicColor, onDynamicColor)
                SwitchRow(R.drawable.lucide_paint_bucket, R.string.settings_pure_black, R.string.settings_pure_black_summary, pureBlack, onPureBlack)
                SwitchRow(R.drawable.lucide_blend, R.string.settings_blur, R.string.settings_blur_summary, blur, onBlur)
                FontScaleRows(chatFontScale, onChatFontScale)
            }
            Section(R.string.settings_behaviour) {
                SwitchRow(R.drawable.lucide_corner_down_left, R.string.settings_enter_to_send, R.string.settings_enter_to_send_summary, enterToSend, onEnterToSend)
                SwitchRow(R.drawable.lucide_vibrate, R.string.settings_haptics, R.string.settings_haptics_summary, haptics, onHaptics)
            }
            Section(R.string.settings_notifications) { notifications() }
            Section(R.string.settings_about) {
                InfoRow(R.drawable.lucide_info, stringResource(R.string.settings_version), versionName)
                InfoRow(R.drawable.lucide_scale, stringResource(R.string.settings_license), stringResource(R.string.settings_license_value))
                InfoRow(R.drawable.lucide_code, stringResource(R.string.settings_source), sourceUrl, onClick = {
                    linkFailed = runCatching { uriHandler.openUri(sourceUrl) }.isFailure
                })
                InfoRow(R.drawable.lucide_file_text, stringResource(R.string.settings_licenses), onClick = onLicenses)
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
    if (themeSheet) {
        ApofisSheet(onDismiss = { themeSheet = false }) {
            for (mode in ThemeMode.entries) {
                SheetRow(
                    themeIcon(mode),
                    stringResource(themeLabel(mode)),
                    onClick = {
                        onThemeMode(mode)
                        themeSheet = false
                    },
                    modifier = Modifier.semantics { selected = mode == themeMode },
                ) {
                    if (mode == themeMode) {
                        Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

private fun themeLabel(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.string.settings_theme_system
    ThemeMode.LIGHT -> R.string.settings_theme_light
    ThemeMode.DARK -> R.string.settings_theme_dark
}

private fun themeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> R.drawable.lucide_smartphone
    ThemeMode.LIGHT -> R.drawable.lucide_sun
    ThemeMode.DARK -> R.drawable.lucide_moon
}

/**
 * The chat text size: a row with the value, a 13 position slider and a preview line under it at the chosen size. While
 * dragged the slider shows the step under the finger; each step it moves gives one selection and one write, never one
 * per frame. Released, it shows the stored value. The preview keeps the height of its largest size, so moving the slider
 * never moves the slider.
 */
@Composable
private fun FontScaleRows(stored: Float, onChange: (Float) -> Unit) {
    val haptics = ApofisTheme.haptics
    var dragged by remember { mutableStateOf<Float?>(null) }
    val shown = dragged ?: stored
    val label = stringResource(R.string.settings_chat_font_size)
    val percent = stringResource(R.string.settings_percent, (shown * 100).roundToInt())
    RowLayout(Modifier, R.drawable.lucide_type, label, percent) {}
    val source = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val colors = SliderDefaults.colors(thumbColor = primary, activeTrackColor = primary, inactiveTrackColor = ApofisTheme.colors.switchOffTrack)
    val state = remember {
        SliderState(shown, ((CHAT_FONT_SCALE_MAX - CHAT_FONT_SCALE_MIN) / 0.05f).roundToInt() - 1, CHAT_FONT_SCALE_MIN..CHAT_FONT_SCALE_MAX)
    }
    state.value = shown // as Material's value overload does: the caller's value is the one shown
    Slider(
        state,
        // Material calls this once per step the thumb reaches, never per frame: one feedback and one write each.
        onValueChange = { value: Float -> dragged = chatFontScaleStep(value).also { haptics.selection(); onChange(it) } },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .semantics {
                contentDescription = label
                stateDescription = percent
            },
        onValueChangeFinished = { dragged = null },
        interactionSource = source,
        thumb = { Box(Modifier.size(20.dp).background(primary, CircleShape).wash(source, CircleShape, ring = onPrimary)) },
        track = { SliderDefaults.Track(it, Modifier.height(4.dp), colors = colors, drawStopIndicator = null, thumbTrackGapSize = 0.dp) },
    )
    val preview = stringResource(R.string.settings_font_preview)
    val style = ChatText.assistant
    Box(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 11.dp), contentAlignment = Alignment.CenterStart) {
        Text(preview, Modifier.alpha(0f).clearAndSetSemantics {}, style = style.copy(fontSize = style.fontSize * CHAT_FONT_SCALE_MAX))
        Text(preview, style = style.copy(fontSize = style.fontSize * shown), color = MaterialTheme.colorScheme.onSurface)
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

/** A row with a [value]; with [onClick] it navigates: a chevron and one selection feedback per tap. */
@Composable
private fun InfoRow(icon: Int, title: String, value: String? = null, onClick: (() -> Unit)? = null) {
    val haptics = ApofisTheme.haptics
    RowLayout(if (onClick != null) Modifier.clickable(role = Role.Button) { haptics.selection(); onClick() } else Modifier, icon, title, value) {
        if (onClick != null) {
            Icon(
                painterResource(R.drawable.lucide_chevron_right),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** One toggleable node with the switch role and its state; one selection feedback per toggle. */
@Composable
private fun SwitchRow(icon: Int, title: Int, summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val haptics = ApofisTheme.haptics
    RowLayout(
        Modifier.toggleable(checked, role = Role.Switch) { haptics.selection(); onChange(it) },
        icon,
        stringResource(title),
        stringResource(summary),
    ) { PillSwitch(checked) }
}

/** 15 sp row, 20 dp icon in a 36 dp slot, 12 by 11 dp padding, at least 48 dp tall; [trailing] sits at the end. */
@Composable
internal fun RowLayout(modifier: Modifier, icon: Int, title: String, value: String? = null, trailing: @Composable () -> Unit) {
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
