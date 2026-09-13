package com.redurbabat.feedback.files

/**
 * Wording for the local share management screen.
 *
 * Deliberately free of `android.*` so the text rules are unit testable: the phrasing of what the
 * owner has handed out is part of the consent surface, not decoration.
 *
 * Ages are expressed in elapsed time rather than calendar days. A calendar day would need the
 * device time zone, and the wall clock is user settable - the same reason the auto-lock policy
 * avoids it. The label may therefore say "vor 23 Stunden" where a calendar would say "gestern";
 * that is the honest reading of the data we actually have.
 */
object FileSharePresentation {

    fun kindLabel(kind: FileShareKind): String = when (kind) {
        FileShareKind.TREE -> "Ordner"
        FileShareKind.FILE -> "Einzelne Datei"
    }

    /**
     * How long ago the owner handed this area over. Returns a neutral label when the clock moved
     * backwards, rather than inventing a negative age.
     */
    fun addedLabel(addedAtEpochMillis: Long, nowEpochMillis: Long): String {
        val elapsed = nowEpochMillis - addedAtEpochMillis
        if (elapsed < 0L) {
            return "freigegeben"
        }
        val hours = elapsed / HOUR_MILLIS
        if (hours < 1L) {
            return "gerade freigegeben"
        }
        if (hours < 24L) {
            return if (hours == 1L) "vor 1 Stunde freigegeben" else "vor $hours Stunden freigegeben"
        }
        val days = elapsed / DAY_MILLIS
        return if (days == 1L) "vor 1 Tag freigegeben" else "vor $days Tagen freigegeben"
    }

    /**
     * Shown when Android no longer reports a grant this app still has on record - the owner
     * withdrew it in system settings, or the volume is gone. Such an area is not silently dropped:
     * the owner should see that something they once shared is no longer reachable.
     */
    fun unavailableNotice(count: Int): String? = when {
        count <= 0 -> null
        count == 1 ->
            "Für einen freigegebenen Bereich hat Android den Zugriff entzogen. " +
                "Er ist nicht mehr abrufbar und kann entfernt werden."
        else ->
            "Für $count freigegebene Bereiche hat Android den Zugriff entzogen. " +
                "Sie sind nicht mehr abrufbar und können entfernt werden."
    }

    /** Summary line under the capability switch. */
    fun shareSummary(availableCount: Int): String = when (availableCount) {
        0 -> "Noch kein Bereich freigegeben. Ohne Freigabe sieht das Control Center nichts."
        1 -> "1 Bereich freigegeben."
        else -> "$availableCount Bereiche freigegeben."
    }

    private const val HOUR_MILLIS = 3_600_000L
    private const val DAY_MILLIS = 86_400_000L
}
