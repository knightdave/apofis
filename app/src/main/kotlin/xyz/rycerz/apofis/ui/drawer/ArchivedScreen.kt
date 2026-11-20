package xyz.rycerz.apofis.ui.drawer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R

/** Archived chats, most recently active first, without groups; long-press offers Unarchive and Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivedScreen(chats: List<DrawerRow.Chat>, actions: DrawerActions, onBack: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archived_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.lucide_arrow_left), contentDescription = stringResource(R.string.back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(chats, key = { it.key }) { chat ->
                ChatRow(
                    chat,
                    onClick = { actions.onSelect(chat.key) },
                    onLongPress = { actions.onLongPress(chat.key) },
                    a11yActions = listOf(
                        stringResource(R.string.drawer_unarchive) to { actions.onArchive(chat.key, false) },
                        stringResource(R.string.drawer_delete) to { actions.onDeleteRequest(chat.key) },
                    ),
                )
            }
        }
    }
}
