package xyz.rycerz.apofis.ui

import androidx.compose.foundation.layout.size
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.R

/** Packaged from the repository's licenses/ directory (see app/build.gradle.kts). */
const val LUCIDE_LICENSE_ASSET = "LUCIDE-LICENSE"
const val COMMONMARK_LICENSE_ASSET = "COMMONMARK-LICENSE"
const val COMMONMARK_NOTICE_ASSET = "COMMONMARK-NOTICE"
const val UNIFIEDPUSH_LICENSE_ASSET = "UNIFIEDPUSH-CONNECTOR-LICENSE"
const val APACHE_LICENSE_ASSET = "APACHE-LICENSE-2.0"

/** The Apache 2.0 libraries in the APK besides the UnifiedPush connector; names are not translated. */
private const val APACHE_LIBRARIES =
    "Kotlin standard library, AndroidX, Jetpack Compose, Room, WorkManager, OkHttp, Okio, kotlinx.serialization, kotlinx.coroutines"

/** Every shipped license: its heading, a line before its text (or none) and its assets. */
private val LICENSES = listOf(
    Triple(R.string.licenses_lucide, "", listOf(LUCIDE_LICENSE_ASSET)),
    // The repository's license at the pinned tag, then the notice the four jars carry (another copyright line).
    Triple(R.string.licenses_commonmark, "", listOf(COMMONMARK_LICENSE_ASSET, COMMONMARK_NOTICE_ASSET)),
    Triple(R.string.licenses_unifiedpush, "", listOf(UNIFIEDPUSH_LICENSE_ASSET)),
    Triple(R.string.licenses_apache, APACHE_LIBRARIES, listOf(APACHE_LICENSE_ASSET)),
)

/** The third-party license texts shipped in the app, verbatim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val texts by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) {
            LICENSES.map { (_, intro, assets) ->
                (listOf(intro).filter { it.isNotEmpty() } + assets.map { a -> context.assets.open(a).bufferedReader().use { it.readText() } }).joinToString("\n\n")
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
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
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            LICENSES.forEachIndexed { i, (title, _, _) ->
                Text(
                    stringResource(title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 24.dp, bottom = 12.dp).semantics { heading() },
                )
                Text(texts.getOrElse(i) { "" }, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
