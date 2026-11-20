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

/** Every shipped license: its heading and its asset. */
private val LICENSES = listOf(
    R.string.licenses_lucide to listOf(LUCIDE_LICENSE_ASSET),
    // The repository's license at the pinned tag, then the notice the four jars carry (another copyright line).
    R.string.licenses_commonmark to listOf(COMMONMARK_LICENSE_ASSET, COMMONMARK_NOTICE_ASSET),
)

/** The third-party license texts shipped in the app, verbatim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val texts by produceState(emptyList<String>()) {
        value = withContext(Dispatchers.IO) { LICENSES.map { (_, assets) -> assets.joinToString("\n\n") { a -> context.assets.open(a).bufferedReader().use { it.readText() } } } }
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
            LICENSES.forEachIndexed { i, (title, _) ->
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
