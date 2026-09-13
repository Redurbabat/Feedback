package com.redurbabat.feedback.security

/**
 * Local actions that weaken protection or hand out a privilege and therefore ask for the app-lock
 * secret again, so an unlocked-but-unattended phone is not enough to perform them.
 */
enum class SensitiveAction(
    val title: String,
    val description: String,
    val confirmLabel: String,
    /** True when the window in [AppLockReauthPolicy] must not be able to skip the prompt. */
    val alwaysRequiresSecret: Boolean,
) {
    DISABLE_APP_LOCK(
        title = "App-Schutz deaktivieren",
        description = "Ohne App-Schutz kann jede Person mit Zugriff auf das entsperrte Telefon " +
            "die Geräteverwaltung öffnen. Zur Bestätigung PIN oder Passphrase eingeben.",
        confirmLabel = "Schutz deaktivieren",
        alwaysRequiresSecret = true,
    ),
    FORGET_REGISTRATION(
        title = "Lokale Kopplung entfernen",
        description = "Das lokale Gerätetoken wird gelöscht und die Verbindung beendet. " +
            "Zur Bestätigung PIN oder Passphrase eingeben.",
        confirmLabel = "Kopplung entfernen",
        alwaysRequiresSecret = false,
    ),
    GRANT_SYSTEM_INFO(
        title = "Systeminformationen freigeben",
        description = "Das Control Center darf dann Modell, Android-Version, Akku, Speicher und " +
            "Netzwerktyp live abfragen. Zur Bestätigung PIN oder Passphrase eingeben.",
        confirmLabel = "Freigeben",
        alwaysRequiresSecret = false,
    ),
    GRANT_MEDIA_PHOTOS(
        title = "Fotos freigeben",
        description = "Das Control Center darf dann die Bilder lesen und herunterladen, die du " +
            "unten ueber Androids Fotoauswahl auswaehlst - und nur diese. Videos sind davon " +
            "nicht betroffen. Zur Bestätigung PIN oder Passphrase eingeben.",
        confirmLabel = "Freigeben",
        alwaysRequiresSecret = false,
    ),
    GRANT_MEDIA_VIDEOS(
        title = "Videos freigeben",
        description = "Das Control Center darf dann die Videos lesen und herunterladen, die du " +
            "unten ueber Androids Fotoauswahl auswaehlst - und nur diese. Fotos sind davon " +
            "nicht betroffen. Zur Bestätigung PIN oder Passphrase eingeben.",
        confirmLabel = "Freigeben",
        alwaysRequiresSecret = false,
    ),
    GRANT_FILES_READ(
        title = "Dateizugriff freigeben",
        description = "Das Control Center darf dann die Bereiche lesen und herunterladen, die du " +
            "unten ausdrücklich freigibst - und nur diese. Es kann nichts ändern, löschen oder " +
            "öffnen. Zur Bestätigung PIN oder Passphrase eingeben.",
        confirmLabel = "Freigeben",
        alwaysRequiresSecret = false,
    ),
}

/**
 * When a sensitive action may reuse a recent unlock instead of asking again.
 *
 * The window is measured on the monotonic clock for the same reason as [AutoLockPolicy]: the wall
 * clock is user settable. Withdrawing a permission is never gated - only granting one is.
 */
object AppLockReauthPolicy {
    const val REAUTH_WINDOW_MILLIS = 60_000L

    fun requiresReauthentication(
        action: SensitiveAction,
        lastAuthenticatedAtElapsedMillis: Long?,
        nowElapsedMillis: Long,
    ): Boolean {
        if (action.alwaysRequiresSecret) {
            return true
        }
        if (lastAuthenticatedAtElapsedMillis == null) {
            return true
        }
        val elapsed = nowElapsedMillis - lastAuthenticatedAtElapsedMillis
        if (elapsed < 0L) {
            // Monotonic source is not trustworthy here; ask again.
            return true
        }
        return elapsed >= REAUTH_WINDOW_MILLIS
    }
}
