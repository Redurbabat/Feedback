package com.redurbabat.feedback.screen

/**
 * Builds the `avc1.PPCCLL` codec string a browser decoder needs, from the encoder's own SPS.
 *
 * The alternative would be to hardcode `avc1.42E01E` (baseline, level 3.0) and hope every phone
 * agrees. They do not: encoders pick their own profile and level, and a decoder configured for
 * the wrong one either refuses or produces garbage. Reading it out of the SPS is the only
 * answer that is true on the device it came from.
 *
 * Pure, so the byte walking is unit tested rather than discovered on a phone.
 */
object AvcCodecString {

    private const val NAL_TYPE_SPS = 7

    /**
     * Null when no SPS is found. The caller then refuses to start rather than guessing: a
     * guessed codec string is a picture that fails to decode for reasons nobody can see.
     */
    fun fromAnnexB(data: ByteArray): String? {
        var index = 0
        while (index + 3 < data.size) {
            val startCodeLength = startCodeLengthAt(data, index)
            if (startCodeLength == 0) {
                index += 1
                continue
            }
            val headerIndex = index + startCodeLength
            if (headerIndex + 3 >= data.size) {
                return null
            }
            val nalType = data[headerIndex].toInt() and 0x1f
            if (nalType == NAL_TYPE_SPS) {
                val profile = data[headerIndex + 1].toInt() and 0xff
                val constraints = data[headerIndex + 2].toInt() and 0xff
                val level = data[headerIndex + 3].toInt() and 0xff
                return "avc1." + hex(profile) + hex(constraints) + hex(level)
            }
            index = headerIndex
        }
        return null
    }

    /** 0 when no start code begins here, otherwise 3 or 4. */
    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        if (index + 2 >= data.size) {
            return 0
        }
        if (data[index].toInt() != 0 || data[index + 1].toInt() != 0) {
            return 0
        }
        if (data[index + 2].toInt() == 1) {
            return 3
        }
        if (index + 3 < data.size && data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1) {
            return 4
        }
        return 0
    }

    private fun hex(value: Int): String {
        val digits = "0123456789ABCDEF"
        return "" + digits[(value ushr 4) and 0x0f] + digits[value and 0x0f]
    }
}

/**
 * The words the owner reads before agreeing, and while it runs.
 *
 * Kept separate and free of Android so the wording is reviewable and testable. It matters more
 * than most strings in this app: it is the part of the consent that says *who* is watching,
 * which Android's own dialog cannot say.
 */
object ScreenSessionPresentation {

    fun consentTitle(): String = "Bildschirm freigeben?"

    fun consentBody(): String =
        "Das Control Center moechte deinen Bildschirm sehen. Sichtbar ist alles, was die Anzeige " +
            "zeigt - auch andere Apps, Benachrichtigungen und Eingaben. Es wird nichts " +
            "aufgezeichnet und kein Ton uebertragen. Danach fragt Android selbst noch einmal."

    fun consentAllow(): String = "Bildschirm zeigen"

    fun consentDecline(): String = "Ablehnen"

    fun notificationTitle(): String = "Bildschirm wird uebertragen"

    fun notificationBody(): String =
        "Das Control Center sieht gerade deinen Bildschirm. Tippe auf Stoppen, um das sofort zu beenden."

    fun notificationStopAction(): String = "Stoppen"

    /** Shown in the app itself, so the owner can see the state without the shade. */
    fun statusLine(active: Boolean): String =
        if (active) "Bildschirmuebertragung laeuft" else "Keine Bildschirmuebertragung"
}
