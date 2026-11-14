package ir.v2max.app.net

/** Choose a release APK that can be installed on this device, never a random ABI split. */
internal object UpdateAssets {
    data class Asset(val name: String, val url: String)
    private const val RELEASE_DOWNLOAD = "https://github.com/rahmaniho/V2max/releases/download/"

    fun select(assets: List<Asset>, supportedAbis: List<String>): Asset? {
        val apks = assets.filter {
            it.name.endsWith(".apk", ignoreCase = true) && it.url.startsWith(RELEASE_DOWNLOAD, ignoreCase = true)
        }
        // Universal APKs are preferable when available, including on emulators and 32-bit devices.
        apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let { return it }
        for (abi in supportedAbis) {
            val normalized = abi.lowercase()
            val pattern = Regex("(?:^|[-_])${Regex.escape(normalized)}(?=$|[-_.])", RegexOption.IGNORE_CASE)
            val match = apks.firstOrNull { asset ->
                pattern.containsMatchIn(asset.name) &&
                    !(normalized == "x86" && asset.name.contains("x86_64", ignoreCase = true))
            }
            if (match != null) return match
        }
        // An incompatible APK is worse than opening the release page for a manual choice.
        return null
    }
}
