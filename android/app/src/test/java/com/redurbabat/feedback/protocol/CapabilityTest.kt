package com.redurbabat.feedback.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityTest {

    @Test
    fun allEightCapabilitiesAreDeclared() {
        assertEquals(8, Capability.values().size)
        assertEquals(Capability.SYSTEM_INFO, Capability.fromWire("system.info"))
        assertEquals(Capability.FILES_READ, Capability.fromWire("files.read"))
        assertEquals(Capability.MEDIA_PHOTOS_READ, Capability.fromWire("media.photos.read"))
        assertEquals(Capability.MEDIA_VIDEOS_READ, Capability.fromWire("media.videos.read"))
        assertEquals(Capability.SCREEN_VIEW, Capability.fromWire("screen.view"))
        assertEquals(Capability.SCREEN_CONTROL, Capability.fromWire("screen.control"))
        assertEquals(Capability.CLIPBOARD_READ, Capability.fromWire("clipboard.read"))
        assertEquals(Capability.CLIPBOARD_WRITE, Capability.fromWire("clipboard.write"))
    }

    /**
     * Guards protocol section 8.1. Flipping a capability to implemented is a protocol change, so
     * this list has to be edited deliberately - never to make a failing test pass.
     */
    @Test
    fun exactlyTheFiveImplementedCapabilitiesAreLiveInV1() {
        val implemented = setOf(
            Capability.SYSTEM_INFO,
            Capability.FILES_READ,
            Capability.MEDIA_PHOTOS_READ,
            Capability.MEDIA_VIDEOS_READ,
            Capability.SCREEN_VIEW,
        )
        for (capability in Capability.values()) {
            assertEquals(
                capability.wireName,
                capability in implemented,
                capability.implemented,
            )
        }
    }

    /**
     * Section 8.4.2: the two media capabilities are separate. Granting one must never be a way to
     * read the other, which is exactly the mistake a shared "media" permission would invite.
     */
    @Test
    fun grantingPhotosDoesNotGrantVideos() {
        val videos = EffectivePermission.evaluate(
            capability = Capability.MEDIA_VIDEOS_READ,
            serverGranted = setOf(Capability.MEDIA_PHOTOS_READ),
            deviceGranted = setOf(Capability.MEDIA_PHOTOS_READ),
            osAvailable = setOf(Capability.MEDIA_PHOTOS_READ, Capability.MEDIA_VIDEOS_READ),
            sessionAuthorized = setOf(Capability.MEDIA_PHOTOS_READ, Capability.MEDIA_VIDEOS_READ),
        )
        assertFalse("photos must not imply videos", videos.effective)

        val photos = EffectivePermission.evaluate(
            capability = Capability.MEDIA_PHOTOS_READ,
            serverGranted = setOf(Capability.MEDIA_VIDEOS_READ),
            deviceGranted = setOf(Capability.MEDIA_VIDEOS_READ),
            osAvailable = setOf(Capability.MEDIA_PHOTOS_READ, Capability.MEDIA_VIDEOS_READ),
            sessionAuthorized = setOf(Capability.MEDIA_PHOTOS_READ, Capability.MEDIA_VIDEOS_READ),
        )
        assertFalse("videos must not imply photos", photos.effective)
    }

    /** And neither of them is a way into files.read, nor the other way round. */
    @Test
    fun mediaAndFilesDoNotImplyEachOther() {
        val files = EffectivePermission.evaluate(
            capability = Capability.FILES_READ,
            serverGranted = setOf(Capability.MEDIA_PHOTOS_READ, Capability.MEDIA_VIDEOS_READ),
            deviceGranted = setOf(Capability.MEDIA_PHOTOS_READ, Capability.MEDIA_VIDEOS_READ),
            osAvailable = setOf(Capability.FILES_READ),
            sessionAuthorized = setOf(Capability.FILES_READ),
        )
        assertFalse("media must not imply files", files.effective)

        val photos = EffectivePermission.evaluate(
            capability = Capability.MEDIA_PHOTOS_READ,
            serverGranted = setOf(Capability.FILES_READ),
            deviceGranted = setOf(Capability.FILES_READ),
            osAvailable = setOf(Capability.MEDIA_PHOTOS_READ),
            sessionAuthorized = setOf(Capability.MEDIA_PHOTOS_READ),
        )
        assertFalse("files must not imply media", photos.effective)
    }

    @Test
    fun anUnimplementedCapabilityIsRefusedAsUnsupported() {
        val permission = EffectivePermission.evaluate(
            capability = Capability.SCREEN_CONTROL,
            serverGranted = setOf(Capability.SCREEN_CONTROL),
            deviceGranted = setOf(Capability.SCREEN_CONTROL),
            osAvailable = setOf(Capability.SCREEN_CONTROL),
            sessionAuthorized = setOf(Capability.SCREEN_CONTROL),
        )
        assertFalse("all four factors must still not be enough", permission.effective)
        assertEquals(ProtocolError.UNSUPPORTED, permission.denialReason())
    }

    /**
     * Section 8.5: seeing the screen and controlling it are separate capabilities, and only the
     * first one exists. A grant of `screen.view` must never be a way into `screen.control` - that
     * is the line between watching and operating somebody's phone.
     */
    @Test
    fun viewingTheScreenDoesNotImplyControllingIt() {
        val control = EffectivePermission.evaluate(
            capability = Capability.SCREEN_CONTROL,
            serverGranted = setOf(Capability.SCREEN_VIEW),
            deviceGranted = setOf(Capability.SCREEN_VIEW),
            osAvailable = setOf(Capability.SCREEN_VIEW, Capability.SCREEN_CONTROL),
            sessionAuthorized = setOf(Capability.SCREEN_VIEW, Capability.SCREEN_CONTROL),
        )
        assertFalse("view must not imply control", control.effective)
    }

    @Test
    fun unknownWireNamesAreDeniedByDefault() {
        assertNull(Capability.fromWire(""))
        assertNull(Capability.fromWire("system.info.all"))
        assertNull(Capability.fromWire("SYSTEM.INFO"))
        assertNull(Capability.fromWire(" system.info"))
        assertNull(Capability.fromWire("shell.exec"))
    }

    @Test
    fun wireListDropsUnknownEntriesInsteadOfFailingOpen() {
        val parsed = Capability.fromWireList(
            listOf("system.info", "shell.exec", "files.read", "files.read"),
        )
        assertEquals(setOf(Capability.SYSTEM_INFO, Capability.FILES_READ), parsed)
        assertTrue(Capability.fromWireList(listOf("nothing.known")).isEmpty())
    }

    @Test
    fun effectiveRequiresAllFourFactors() {
        assertTrue(permission(true, true, true, true).effective)
        assertFalse(permission(false, true, true, true).effective)
        assertFalse(permission(true, false, true, true).effective)
        assertFalse(permission(true, true, false, true).effective)
        assertFalse(permission(true, true, true, false).effective)
        assertFalse(permission(false, false, false, false).effective)
    }

    @Test
    fun grantedCapabilityHasNoDenialReason() {
        assertNull(permission(true, true, true, true).denialReason())
    }

    @Test
    fun denialReasonsAreDistinguishable() {
        assertEquals(
            ProtocolError.CAPABILITY_DENIED,
            permission(false, true, true, true).denialReason(),
        )
        assertEquals(
            ProtocolError.CAPABILITY_DENIED,
            permission(true, false, true, true).denialReason(),
        )
        assertEquals(
            ProtocolError.PERMISSION_REQUIRED,
            permission(true, true, false, true).denialReason(),
        )
        assertEquals(
            ProtocolError.SESSION_EXPIRED,
            permission(true, true, true, false).denialReason(),
        )
    }

    @Test
    fun unimplementedCapabilityStaysUnsupportedEvenWhenFullyGranted() {
        val screenControl = EffectivePermission(
            capability = Capability.SCREEN_CONTROL,
            serverGranted = true,
            deviceGranted = true,
            osAvailable = true,
            sessionAuthorized = true,
        )
        assertFalse(screenControl.effective)
        assertEquals(ProtocolError.UNSUPPORTED, screenControl.denialReason())
    }

    @Test
    fun evaluateReadsTheFourSetsIndependently() {
        val permission = EffectivePermission.evaluate(
            capability = Capability.SYSTEM_INFO,
            serverGranted = setOf(Capability.SYSTEM_INFO),
            deviceGranted = setOf(Capability.SYSTEM_INFO),
            osAvailable = setOf(Capability.SYSTEM_INFO),
            sessionAuthorized = emptySet(),
        )
        assertFalse(permission.effective)
        assertEquals(ProtocolError.SESSION_EXPIRED, permission.denialReason())
    }

    @Test
    fun thereIsNoImplicitHierarchy() {
        val granted = Capability.fromWireList(listOf("screen.control"))
        assertFalse(granted.contains(Capability.FILES_READ))
        assertFalse(granted.contains(Capability.SCREEN_VIEW))
        assertFalse(granted.contains(Capability.SYSTEM_INFO))
    }

    @Test
    fun errorCodesAreCompleteAndDenyUnknownCodes() {
        assertEquals(13, ProtocolError.values().size)
        assertEquals(ProtocolError.SESSION_EXPIRED, ProtocolError.fromCode("SESSION_EXPIRED"))
        assertEquals(409, ProtocolError.SESSION_EXPIRED.httpStatus)
        assertEquals(410, ProtocolError.PAIRING_EXPIRED.httpStatus)
        assertEquals(429, ProtocolError.RATE_LIMITED.httpStatus)
        assertNull(ProtocolError.fromCode("session_expired"))
        assertNull(ProtocolError.fromCode("UNKNOWN"))
    }

    private fun permission(
        serverGranted: Boolean,
        deviceGranted: Boolean,
        osAvailable: Boolean,
        sessionAuthorized: Boolean,
    ): EffectivePermission = EffectivePermission(
        capability = Capability.SYSTEM_INFO,
        serverGranted = serverGranted,
        deviceGranted = deviceGranted,
        osAvailable = osAvailable,
        sessionAuthorized = sessionAuthorized,
    )
}
