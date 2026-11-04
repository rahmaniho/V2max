package ir.v2max.core.test

import ir.v2max.core.geo.CountryFlags

/**
 * Tests for offline country detection from a server name.
 *
 * The matching order matters more than any single lookup: emoji first, then a
 * full Persian or English name, then a two-letter code that stands alone as a
 * token. The tests below pin that order, because a subscription named
 * "🇫🇷 germany" must resolve to France, not Germany.
 */
object CountryFlagsTests {

    fun run() {
        T.group("CountryFlags")

        // ---- emoji flag
        val flag = T.notNull("emoji resolves", CountryFlags.resolve("🇩🇪"))
        T.eq("emoji code", "DE", flag?.code)
        T.eq("emoji name is Persian", "آلمان", flag?.name)

        // ---- full English name, case-insensitive
        T.eq("english name", "NL", CountryFlags.resolve("Netherlands node 3")?.code)
        T.eq("english name lowercase", "NL", CountryFlags.resolve("netherlands")?.code)
        T.eq("english name uppercase", "NL", CountryFlags.resolve("NETHERLANDS")?.code)
        T.eq("multi-word english name", "GB", CountryFlags.resolve("United Kingdom")?.code)

        // ---- full Persian name
        T.eq("persian name", "DE", CountryFlags.resolve("سرور آلمان")?.code)
        T.eq("persian name value", "آلمان", CountryFlags.resolve("آلمان")?.name)

        // ---- two-letter code as a standalone token
        T.eq("code after dash", "DE", CountryFlags.resolve("VLESS-DE-1")?.code)
        T.eq("code lowercase in name", "DE", CountryFlags.resolve("node-de")?.code)
        T.eq("code in brackets", "FR", CountryFlags.resolve("[FR] Paris")?.code)
        T.eq("code after underscore", "SE", CountryFlags.resolve("vless_se_01")?.code)
        T.eq("code before pipe", "TR", CountryFlags.resolve("TR|Istanbul")?.code)
        T.eq("code after colon", "PL", CountryFlags.resolve("node:PL")?.code)
        T.eq("code after middot", "AT", CountryFlags.resolve("Vienna·AT")?.code)

        // A code only counts as a whole token, never as a substring — otherwise
        // "DELUXE" would resolve to Germany.
        T.eq("code inside a word is not a match", null, CountryFlags.resolve("DELUXE"))
        T.eq("code inside a word, no other hint", null, CountryFlags.resolve("NODE"))

        // ---- precedence: emoji beats a full name in the same string
        T.eq(
            "emoji wins over english name",
            "FR",
            CountryFlags.resolve("🇫🇷 germany")?.code,
        )

        // ---- no match
        T.eq("unknown name", null, CountryFlags.resolve("just some server"))
        T.eq("blank name", null, CountryFlags.resolve(""))
        T.eq("whitespace name", null, CountryFlags.resolve("   "))

        // ---- emojiFor from the table
        T.eq("emojiFor from table", "🇩🇪", CountryFlags.emojiFor("DE"))
        T.eq("emojiFor is case-insensitive", "🇩🇪", CountryFlags.emojiFor("de"))
        T.eq("emojiFor iran", "🇮🇷", CountryFlags.emojiFor("IR"))

        // ---- emojiFor computed for a country missing from the table.
        // Built from code points rather than a literal so the expectation is
        // unambiguous regardless of how the source file is encoded.
        val computed = String(Character.toChars(0x1F1E6 + ('N' - 'A'))) +
            String(Character.toChars(0x1F1E6 + ('Z' - 'A')))
        T.eq("emojiFor computes for unknown code", computed, CountryFlags.emojiFor("NZ"))

        // ---- emojiFor rejects anything that is not a two-letter code
        T.eq("emojiFor empty for blank", "", CountryFlags.emojiFor(""))
        T.eq("emojiFor empty for one letter", "", CountryFlags.emojiFor("D"))
        T.eq("emojiFor empty for three letters", "", CountryFlags.emojiFor("DEU"))

        // ---- table and detector agree for every known code
        // If emojiFor and resolve disagreed for some country, the flag shown in
        // the UI would not match the country the server was tagged as.
        val sampled = listOf(
            "IR" to "🇮🇷", "DE" to "🇩🇪", "NL" to "🇳🇱", "FR" to "🇫🇷",
            "US" to "🇺🇸", "TR" to "🇹🇷", "JP" to "🇯🇵", "SG" to "🇸🇬",
        )
        sampled.forEach { (code, emoji) ->
            T.eq("emojiFor matches table for $code", emoji, CountryFlags.emojiFor(code))
            T.eq("emoji resolves back to $code", code, CountryFlags.resolve(emoji)?.code)
        }

        // Resolving a flag then rendering it back must be stable.
        sampled.forEach { (code, emoji) ->
            val resolved = CountryFlags.resolve("server $emoji 42")?.code.orEmpty()
            T.eq("round-trip stable for $code", emoji, CountryFlags.emojiFor(resolved))
        }
    }
}
