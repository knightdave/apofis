package xyz.rycerz.apofis.api

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The exact JSON sent to `POST /v1/runs`; callers store it so a retry sends the same bytes. */
fun buildRunBody(sessionId: String, text: String): String = buildJsonObject {
    put("session_id", sessionId)
    put("input", text)
}.toString()
