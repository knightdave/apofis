package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.scaled

/**
 * A message's raw text, selectable, on its own page: selection across lazy list rows is unreliable. Text over
 * [SELECT_MAX_CHARS] is refused with a message instead of being laid out.
 */
@Composable
fun SelectTextScreen(text: String, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(scheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ApofisIconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                Icon(painterResource(R.drawable.lucide_arrow_left), contentDescription = null, Modifier.size(20.dp), tint = scheme.onSurface)
            }
            Text(stringResource(R.string.select_title), Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = scheme.onSurface)
        }
        if (text.length > SELECT_MAX_CHARS) {
            Text(stringResource(R.string.select_too_large), Modifier.padding(20.dp), style = MaterialTheme.typography.labelMedium, color = ApofisTheme.colors.muted)
            return@Column
        }
        SelectionContainer(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(text, Modifier.fillMaxWidth(), style = scaled(ChatText.assistant), color = scheme.onSurface)
        }
    }
}
