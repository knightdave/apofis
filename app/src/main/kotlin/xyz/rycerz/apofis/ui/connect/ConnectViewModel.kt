package xyz.rycerz.apofis.ui.connect

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.settings.Credentials

enum class Field { URL, KEY }

enum class Reason {
    MISSING_SCHEME,
    MALFORMED_URL,
    URL_EXTRA_PARTS, // user:password@, ?query or #fragment: often a token, and never stored or shown
    UNREACHABLE,
    NOT_HERMES,
    KEY_REQUIRED,
    KEY_INVALID, // a character an HTTP header cannot carry
    KEY_REJECTED,
    SAVE_FAILED,
}

sealed interface ConnectState {
    data object Idle : ConnectState
    data object Testing : ConnectState
    data class Failed(val field: Field, val reason: Reason) : ConnectState

    /** The server works, but it is not the one the cached chats came from: saving clears the cache. */
    data object ConfirmServerChange : ConnectState
    data object Connected : ConnectState
}

/**
 * Tests the entered server with `capabilities()` and saves the credentials only when it answers.
 * With [current] set (opened from Settings) the URL is prefilled and an empty key field keeps the current key,
 * but only for the same URL: the stored key is never sent to another server.
 * Its life is the screen's: leaving cancels the probe (nothing is saved afterwards) and forgets the typed key.
 */
class ConnectViewModel(
    private val current: Credentials?,
    private val willClearCache: suspend (baseUrl: String) -> Boolean,
    private val save: suspend (Credentials) -> Unit,
) : ViewModel() {
    var url by mutableStateOf(current?.baseUrl.orEmpty())
        private set
    /** A secure field's state: the key is not held in a plain String state or offered for copying. */
    val apiKey = TextFieldState()
    var state by mutableStateOf<ConnectState>(ConnectState.Idle)
        private set
    private var pending: Credentials? = null

    val keepsCurrentKey: Boolean get() = current != null
    val httpWarning: Boolean get() = url.trim().startsWith("http://", ignoreCase = true)

    fun onUrlChange(value: String) {
        url = value
        if (state is ConnectState.Failed) state = ConnectState.Idle
    }

    fun onKeyChange(value: String) {
        apiKey.setTextAndPlaceCursorAtEnd(value)
        onKeyEdited()
    }

    fun onKeyEdited() {
        if (state is ConnectState.Failed) state = ConnectState.Idle
    }

    /** Returns the test's job, or null when the input was rejected without a request. */
    fun connect(): Job? {
        if (state == ConnectState.Testing) return null
        val baseUrl = url.trim()
        if (!baseUrl.startsWith("http://", true) && !baseUrl.startsWith("https://", true)) return fail(Field.URL, Reason.MISSING_SCHEME)
        val parsed = baseUrl.toHttpUrlOrNull() ?: return fail(Field.URL, Reason.MALFORMED_URL)
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty() || parsed.query != null || parsed.fragment != null) {
            return fail(Field.URL, Reason.URL_EXTRA_PARTS)
        }
        val key = apiKey.text.trim().toString().ifEmpty { current?.takeIf { it.baseUrl == baseUrl }?.apiKey } ?: return fail(Field.KEY, Reason.KEY_REQUIRED)
        if (key.any { it != '\t' && it !in ' '..'~' }) return fail(Field.KEY, Reason.KEY_INVALID)
        val api = try {
            HermesApi(baseUrl, key)
        } catch (_: IllegalArgumentException) {
            return fail(Field.URL, Reason.MALFORMED_URL)
        }
        state = ConnectState.Testing
        return viewModelScope.launch { state = probe(api, Credentials(baseUrl, key)) }
    }

    // Every failure other than cancellation ends as a field error.
    private suspend fun probe(api: HermesApi, c: Credentials): ConnectState {
        try {
            api.capabilities()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiError) {
            return when (e) {
                ApiError.Unauthorized -> ConnectState.Failed(Field.KEY, Reason.KEY_REJECTED)
                is ApiError.Network -> ConnectState.Failed(Field.URL, Reason.UNREACHABLE)
                else -> ConnectState.Failed(Field.URL, Reason.NOT_HERMES)
            }
        } catch (_: Exception) {
            return ConnectState.Failed(Field.URL, Reason.NOT_HERMES)
        }
        val clears = try {
            willClearCache(c.baseUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return ConnectState.Failed(Field.KEY, Reason.SAVE_FAILED)
        }
        if (clears) {
            pending = c
            return ConnectState.ConfirmServerChange
        }
        return saveOrFail(c)
    }

    fun confirmServerChange(): Job? {
        val c = pending ?: return null
        pending = null
        state = ConnectState.Testing
        return viewModelScope.launch { state = saveOrFail(c) }
    }

    fun cancelServerChange() {
        pending = null
        state = ConnectState.Idle
    }

    // Storage or cache clearing can fail (disk full, keystore error); show it instead of crashing.
    private suspend fun saveOrFail(c: Credentials): ConnectState = try {
        currentCoroutineContext().ensureActive() // the screen may have gone while the cache was checked
        save(c)
        ConnectState.Connected
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        ConnectState.Failed(Field.KEY, Reason.SAVE_FAILED)
    }

    override fun onCleared() {
        pending = null
        apiKey.clearText()
    }

    private fun fail(field: Field, reason: Reason): Job? {
        state = ConnectState.Failed(field, reason)
        return null
    }
}
