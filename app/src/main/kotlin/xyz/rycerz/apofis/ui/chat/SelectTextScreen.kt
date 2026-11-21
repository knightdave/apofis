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
import androidx.compose.runtime.remember
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
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.scaled

/**
 * A message's raw text (line endings LF), selectable, on its own page: selection across lazy list rows is unreliable.
 * Any length: the text is shown in bounded pieces ([textPages]), one lazy item each, and selection works within a
 * piece. The pieces concatenate to the text exactly.
 */
@Composable
fun SelectTextScreen(text: String, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ends = remember(text) { textPages(text) }
    Column(Modifier.fillMaxSize().background(scheme.surface)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ApofisIconButton(onClick = onBack, contentDescription = stringResource(R.string.back)) {
                Icon(painterResource(R.drawable.lucide_arrow_left), contentDescription = null, Modifier.size(20.dp), tint = scheme.onSurface)
            }
            Text(stringResource(R.string.select_title), Modifier.padding(start = 8.dp), style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium), color = scheme.onSurface)
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            items(ends.size, key = { it }) { i ->
                val piece = remember(text, i) { selectPiece(text, ends, i) }
                SelectionContainer {
                    Text(piece, Modifier.fillMaxWidth(), style = scaled(ChatText.assistant), color = scheme.onSurface)
                }
            }
        }
    }
}

/**
 * Piece [i] of [text] as shown: LF line endings, and without the line break a piece after it begins with (the next
 * lazy item starts on a new line already, so it would show as an extra empty line).
 */
internal fun selectPiece(text: String, ends: IntArray, i: Int): String {
    val piece = lf(text.substring(if (i == 0) 0 else ends[i - 1], ends[i]))
    return if (i < ends.size - 1) piece.removeSuffix("\n") else piece
}
