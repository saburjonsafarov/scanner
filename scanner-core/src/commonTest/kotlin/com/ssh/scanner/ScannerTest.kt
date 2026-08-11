package com.ssh.scanner

import com.ssh.scanner.qr.QrTestEncoder
import com.ssh.scanner.qr.QrTestRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ScannerTest {

    private fun frameWith(text: String) = QrTestRenderer.render(QrTestEncoder.encode(text))

    @Test
    fun returnsPhoneWhenPhoneIsRequested() {
        val scanner = Scanner(
            ScannerConfig(targets = setOf(ScanTarget.PhoneNumber), defaultCountryCode = "992"),
        )

        val results = scanner.scan(frameWith("TEL:+992927771234"))

        assertEquals(1, results.size)
        val content = results.single().content
        assertIs<ScanContent.Phone>(content)
        assertEquals("+992927771234", content.e164)
    }

    @Test
    fun filtersOutContentThatDoesNotMatchTargets() {
        // В кадре ссылка, а просили только Wi-Fi — публиковать нечего.
        val scanner = Scanner(ScannerConfig(targets = setOf(ScanTarget.Wifi)))

        assertTrue(scanner.scan(frameWith("HTTPS://SSHORG.COM")).isEmpty())
    }

    @Test
    fun qrTargetAcceptsAnyPayload() {
        val scanner = Scanner(ScannerConfig(targets = setOf(ScanTarget.QrCode)))

        val results = scanner.scan(frameWith("просто заметка"))

        assertEquals(1, results.size)
        assertIs<ScanContent.Text>(results.single().content)
        assertEquals(ScanFormat.QrCode, results.single().format)
    }

    @Test
    fun reportsQuadForFoundObject() {
        val scanner = Scanner(ScannerConfig(targets = setOf(ScanTarget.QrCode)))
        val image = frameWith("QUAD")

        val quad = scanner.scan(image).single().quad

        assertTrue(quad.area > 0f)
        assertEquals(4, quad.corners.size)
        assertTrue(quad.center.x in (image.width * 0.3f)..(image.width * 0.7f))
    }

    @Test
    fun emptyFrameYieldsNothingWithoutThrowing() {
        val scanner = Scanner(ScannerConfig(targets = setOf(ScanTarget.QrCode)))
        val blank = com.ssh.scanner.image.GrayImage(ByteArray(64 * 64) { 200.toByte() }, 64, 64)

        assertTrue(scanner.scan(blank).isEmpty())
    }

    @Test
    fun contactWithPhoneSatisfiesPhoneTarget() {
        val scanner = Scanner(
            ScannerConfig(targets = setOf(ScanTarget.PhoneNumber), defaultCountryCode = "992"),
        )

        val results = scanner.scan(frameWith("MECARD:N:Сафаров,Сабурджон;TEL:+992927771234;;"))

        assertEquals(1, results.size)
        assertIs<ScanContent.Contact>(results.single().content)
    }
}
