package xyz.rycerz.apofis.api

/** Every failure of a [HermesApi] call. Messages never carry the API key or server-supplied text. */
sealed class ApiError : Exception() {
    data object Unauthorized : ApiError()
    data object NotFound : ApiError()
    data class Conflict(val code: String) : ApiError()
    data class Unavailable(val retryAfterSeconds: Long?) : ApiError()
    data class Http(val status: Int, val code: String?) : ApiError()
    data class Network(override val cause: Throwable) : ApiError()
}
