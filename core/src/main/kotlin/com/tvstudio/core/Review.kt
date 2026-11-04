package com.tvstudio.core

enum class Category(val title: String) {
    TELEMETRY("Tracking and analytics"),
    ADS("Ads and promoted content"),
    APPS_YOU_MAY_USE("Apps you may use"),
    OTHER("Other apps and services"),
    SYSTEM("Android system"),
}

object PackageClassifier {
    /** Streaming, voice, casting and updates are the owner's call, so they're never ticked for them. */
    private val MAY_USE = Regex(
        "netflix|youtube|primevideo|avod|hotstar|disney|hulu|spotify|plex|kodi|hbo|peacock|paramount|sonyliv" +
            "|zee5|jiocinema|twitch|vlc|music|voice|assistant|katniss|alexa|cast|miracast|mediashell|airplay" +
            "|update|ota|fota|gamepad|livetv|browser",
    )
    private val TELEMETRY = Regex("analytics|telemetry|statistic|crashreport|usagereport|tracking")
    private val ADS = Regex("""(^|[._])ads?([._]|$)|adservice|advert|promo|recommend|sponsor|patchwall|contentrow""")

    fun classify(pkg: String): Category {
        val name = pkg.lowercase()
        return when {
            MAY_USE.containsMatchIn(name) -> Category.APPS_YOU_MAY_USE
            TELEMETRY.containsMatchIn(name) -> Category.TELEMETRY
            ADS.containsMatchIn(name) -> Category.ADS
            name == "android" || name.startsWith("android.") || name.startsWith("com.android.") ||
                name.startsWith("com.google.android.") -> Category.SYSTEM
            else -> Category.OTHER
        }
    }
}

/** One row of the review checklist. A row with a [lockedBecause] can never be ticked. */
data class ReviewItem(
    val pkg: String,
    val category: Category,
    val note: String?,
    val lockedBecause: BlockReason?,
    val preselected: Boolean,
)

object ReviewBuilder {
    /** A tested list: tick its entries, except what the person may use. Absent or off entries are dropped. */
    fun fromList(list: ParsedList, states: PackageStates, facts: TvFacts): List<ReviewItem> =
        list.entries.distinctBy { it.pkg }.filter { it.pkg in states.enabled }.map { entry ->
            val locked = SafetyRules.staticBlock(entry.pkg) ?: facts.block(entry.pkg)
            val category = PackageClassifier.classify(entry.pkg)
            ReviewItem(
                pkg = entry.pkg,
                category = if (category == Category.SYSTEM) Category.OTHER else category,
                note = entry.note ?: entry.section,
                lockedBecause = locked,
                preselected = locked == null && category != Category.APPS_YOU_MAY_USE,
            )
        }.sorted()

    /**
     * No list for this TV. Names can mislead, so only clear tracking and ad names start ticked, and
     * Android's own packages aren't offered at all.
     */
    fun fromScan(states: PackageStates, facts: TvFacts): List<ReviewItem> =
        states.enabled.mapNotNull { pkg ->
            val category = PackageClassifier.classify(pkg)
            if (category == Category.SYSTEM) return@mapNotNull null
            val locked = SafetyRules.staticBlock(pkg) ?: facts.block(pkg)
            ReviewItem(
                pkg = pkg,
                category = category,
                note = null,
                lockedBecause = locked,
                preselected = locked == null && (category == Category.TELEMETRY || category == Category.ADS),
            )
        }.sorted()

    private fun List<ReviewItem>.sorted() = sortedWith(compareBy({ it.category.ordinal }, { it.pkg }))
}
