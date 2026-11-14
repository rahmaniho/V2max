package ir.v2max.core.test

import ir.v2max.core.model.PublicSources

/**
 * Tests for the built-in source catalog.
 *
 * The list itself is data that changes often, so these assert the invariants
 * that must hold whatever is in it: unique URLs, a positive cap, and — the one
 * that was actually broken — that every lookup normalises the URL the same way.
 */
object PublicSourcesTests {

    fun run() {
        T.group("PublicSources")

        val all = PublicSources.ALL
        T.ok("the catalog is not empty", all.isNotEmpty()) { "size=${all.size}" }

        // ---- catalog invariants
        val urls = all.map { PublicSources.normalize(it.url) }
        T.eq("every URL is unique", urls.size, urls.distinct().size)
        T.ok("every source has a name", all.all { it.name.isNotBlank() }) {
            "blank: ${all.filter { it.name.isBlank() }.size}"
        }
        T.ok("every URL is https", all.all { it.url.startsWith("https://") }) {
            "offenders=${all.filterNot { it.url.startsWith("https://") }.map { it.url }}"
        }
        T.ok("every cap is positive", all.all { it.maxConfigs > 0 }) {
            "offenders=${all.filter { it.maxConfigs <= 0 }.map { it.url }}"
        }

        // ---- DEFAULT is a prefix of ALL, so adding a source cannot silently
        // change which ones a fresh install enables.
        T.ok("DEFAULT is not empty", PublicSources.DEFAULT.isNotEmpty())
        T.ok("DEFAULT is a prefix of ALL", all.take(PublicSources.DEFAULT.size) == PublicSources.DEFAULT) {
            "default=${PublicSources.DEFAULT.map { it.url }}"
        }
        T.ok("DEFAULT is smaller than the whole catalog", PublicSources.DEFAULT.size < all.size) {
            "${PublicSources.DEFAULT.size} of ${all.size}"
        }

        // ---- normalize
        T.eq("normalize trims whitespace", "https://a.test/x", PublicSources.normalize("  https://a.test/x  "))
        T.eq("normalize strips one trailing slash", "https://a.test/x", PublicSources.normalize("https://a.test/x/"))
        T.eq("normalize keeps inner slashes", "https://a.test/x/y", PublicSources.normalize("https://a.test/x/y"))
        T.eq("normalize is idempotent", PublicSources.normalize("https://a.test/x"), PublicSources.normalize(PublicSources.normalize("https://a.test/x/")))

        // ---- forUrl finds every catalog entry, however the URL is written
        for (source in all) {
            T.notNull("forUrl finds ${source.name}", PublicSources.forUrl(source.url))
            T.eq("forUrl tolerates a trailing slash", source.url, PublicSources.forUrl(source.url + "/")?.url)
            T.eq("forUrl tolerates whitespace", source.url, PublicSources.forUrl("  ${source.url}  ")?.url)
        }

        // A URL that is not in the catalog must not match anything — this is what
        // makes forUrl safe as a "is this a built-in source?" test.
        T.eq("an unknown URL finds nothing", null, PublicSources.forUrl("https://not-in-catalog.test/sub"))
        T.eq("a blank URL finds nothing", null, PublicSources.forUrl(""))

        // The bug this pins: a stored built-in subscription whose URL picked up a
        // trailing slash used to be compared raw, so it looked unknown and its
        // sync was skipped. It must resolve to the same source either way.
        val first = all.first()
        T.eq(
            "a trailing slash resolves to the same source",
            PublicSources.forUrl(first.url),
            PublicSources.forUrl(first.url + "/"),
        )
    }
}
