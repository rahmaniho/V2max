package ir.v2max.app.data

/** A display hint only: connecting always requires a fresh protocol preflight. */
internal object ServerDisplayOrder {
    val fastest: Comparator<ServerConfig> = compareBy<ServerConfig> {
        it.recordedRealPing().takeIf { ping -> ping > 0 } ?: Int.MAX_VALUE
    }.thenByDescending { if (it.recordedRealPing() > 0) it.testedAt else 0L }
}
