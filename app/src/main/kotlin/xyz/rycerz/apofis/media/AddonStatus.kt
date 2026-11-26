package xyz.rycerz.apofis.media

import xyz.rycerz.apofis.images.ImageFormat

/** The protocol of the picture add-on this app speaks; the release pin check compares it with the plugin's. */
const val APP_PROTOCOL = 1

/** What the app knows about the picture add-on; the ping never answers UNKNOWN or CHECKING. */
enum class AddonStatus { UNKNOWN, CHECKING, NEEDED, READY, NOT_READY, APP_OLD, ERROR }

/** The negotiated offer of a READY add-on: the byte cap (at most 10 MiB) and the picture formats both sides know. */
data class AddonLimits(val maxBytes: Int, val types: Set<ImageFormat>)
