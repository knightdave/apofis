package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.scaled

/**
 * A message's raw text (line endings LF), selectable, on its own page: selection across lazy list rows is unreliable.
 * Any length: the text is shown in [pieceEnd] pieces, one lazy item each, found as the reader reaches the last one
 * (opening reads one piece, never the whole message); selection works within a piece, and the shown pieces
 * concatenate to the text exactly. How many pieces were found is saved, so a deep scroll position survives recreation.
 */
@Composable
fun SelectTextScreen(text: String, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ends = remember(text) { ArrayList<Int>() } // a cache of piece ends, filled in order as pieces are shown
    var found by rememberSaveable(text) { mutableIntStateOf(1) }
    fun end(i: Int): Int {
        while (ends.size <= i) ends += pieceEnd(text, ends.lastOrNull() ?: 0)
        return ends[i]
    }
    Column(Modifier.fillMaxSize().background(scheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ApofisIconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                Icon(painterResource(R.drawable.lucide_arrow_left), contentDescription = null, Modifier.size(20.dp), tint = scheme.onSurface)
            }
            Text(stringResource(R.string.select_title), Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = scheme.onSurface)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            items(found, key = { it }) { i ->
                val e = end(i)
                if (i == found - 1 && e < text.length) LaunchedEffect(i) { found = i + 2 } // the next piece, once this one is shown
                val piece = remember(text, i) { lf(text.substring(if (i == 0) 0 else end(i - 1), e)) }
                SelectionContainer {
                    Text(piece, Modifier.fillMaxWidth().testTag("select.piece:$i"), style = scaled(ChatText.assistant), color = scheme.onSurface)
                }
            }
        }
    }
}
