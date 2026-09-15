package com.redurbabat.feedback.pairing

import java.util.concurrent.atomic.AtomicReference

/**
 * One setup link that reached the app, reduced to what the management UI is allowed to see: the
 * normalised `https://host[:port]` origin the link named, or null when it named none.
 *
 * A null [offeredOrigin] is not "no link arrived". It is "a link arrived and could not be read",
 * and that is an outcome the owner has to be shown - THREAT_MODEL 4.20 promises that a refused
 * address is shown as a refusal, and a link that is dropped on the floor keeps that promise for
 * nobody. "No link arrived" is the absence of an arrival, which is what a null from
 * [SetupLinkHandoff.take] means. The two used to be the same null, and that is how an unreadable
 * link - a forwarded one with `?utm_source=...` is enough, since the intent filter matches on the
 * path alone - came to open the app onto an empty field with nothing on screen.
 */
data class SetupLinkArrival(val offeredOrigin: String?)

/**
 * How [SetupLinkActivity] hands a parsed link to the management UI: process-internal state, read
 * once and cleared.
 *
 * Why not an intent extra. [com.redurbabat.feedback.MainActivity] is the LAUNCHER activity and is
 * therefore exported, so any installed app can start it with any extras it likes. An extra whose
 * meaning is "a setup link said so" is then an assertion by an arbitrary caller: even though the
 * equality rule in [SetupLinkPolicy] still refuses every foreign origin, a caller could make the
 * app state "Serveradresse aus dem Einrichtungslink uebernommen" when no link was ever tapped,
 * overwrite the one message slot that carries warnings such as a failed Keystore load, clear a
 * visible pairing error, or overwrite a real link that is buffered behind the app lock.
 *
 * Why not a caller check either. `getCallingActivity()` is null unless the activity was started
 * for a result, and `referrer` is filled in only for some launch paths; a check that is absent
 * depending on how the app happened to be started is not a check, and whichever way it fails is
 * wrong - open, and it guards nothing; closed, and a real link stops working. Process-internal
 * state has no caller to authenticate in the first place: only code inside this process can write
 * it, and no intent from outside can reach it.
 *
 * A cold start works because both activities live in the same process: [SetupLinkActivity] runs to
 * completion, including this write, before `MainActivity.onCreate` reads it. The cost is that the
 * value does not survive the process dying between the two, unlike an extra, which is parcelled.
 * The write is immediately followed by starting MainActivity from a foreground process, so that
 * window is small - and a lost link means the owner taps it again, which is the harmless direction
 * to fail in. Inventing a link that was never tapped is not.
 */
object SetupLinkHandoff {

    private val pending = AtomicReference<SetupLinkArrival?>(null)

    /** Replaces whatever was not picked up yet: the newest tap is the one the owner meant. */
    fun offer(arrival: SetupLinkArrival) {
        pending.set(arrival)
    }

    /**
     * Returns the arrival and clears it, so that an activity recreation - a rotation, a theme
     * change - does not offer the same link a second time.
     */
    fun take(): SetupLinkArrival? = pending.getAndSet(null)
}
