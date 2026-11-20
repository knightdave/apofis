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

/** The third-party license texts shipped in the app, verbatim. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lucide by produceState("") {
        value = withContext(Dispatchers.IO) { context.assets.open(LUCIDE_LICENSE_ASSET).bufferedReader().use { it.readText() } }
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
            Text(
                stringResource(R.string.licenses_lucide),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() },
            )
            Text(lucide, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
