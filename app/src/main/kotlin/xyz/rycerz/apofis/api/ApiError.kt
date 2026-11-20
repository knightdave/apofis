package xyz.rycerz.apofis.api

/**
 * Every failure of a [HermesApi] call. No subclass carries server-supplied or transport text in
 * `message` or `toString()`: codes are filtered to `[a-z0-9_]{1,64}` and [Network] names only the cause's class.
 */
sealed class ApiError : Exception() {
    data object Unauthorized : ApiError()
    data object NotFound : ApiError()
    data class Conflict(val code: String) : ApiError()
    data class Unavailable(val retryAfterSeconds: Long?) : ApiError()

    /** A 2xx response whose body does not match the expected model, or an event stream that is not `text/event-stream`. */
    data object Protocol : ApiError()

    /** Any other non-2xx status, including 3xx (redirects are never followed). */
    data class Http(val status: Int, val code: String?) : ApiError()

    /** An I/O failure at any point of the exchange. [cause] is kept for debugging; its text never reaches [message]. */
    data class Network(override val cause: Throwable) : ApiError() {
        override val message: String get() = "Network(${cause::class.java.simpleName})"
        override fun toString() = message
    }
}
