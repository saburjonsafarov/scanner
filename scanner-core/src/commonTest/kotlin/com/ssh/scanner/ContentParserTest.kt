package com.ssh.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentParserTest {

    private fun parse(raw: String, countryCode: String? = "992") =
        ContentParser.parse(raw, countryCode)

    @Test
    fun recognisesUrl() {
        val content = parse("https://sshorg.com/scan?id=7")

        assertIs<ScanContent.Url>(content)
        assertEquals("https://sshorg.com/scan?id=7", content.address)
    }

    @Test
    fun recognisesTelScheme() {
        val content = parse("tel:+992 92 777 12 34")

        assertIs<ScanContent.Phone>(content)
        assertEquals("+992927771234", content.e164)
    }

    @Test
    fun normalisesPhoneWritingVariants() {
        val expected = "+992927771234"
        listOf(
            "+992927771234",
            "+992 92 777-12-34",
            "992927771234",
            "00992927771234",
            "(992) 92.777.12.34",
            "927771234",
        ).forEach { input ->
            assertEquals(expected, ContentParser.normalizePhone(input, "992"), "вариант «$input»")
        }
    }

    @Test
    fun refusesBareNumberWithoutCountryCode() {
        // Девятизначный номер без кода страны принадлежит разным людям в разных странах —
        // угадывать нельзя.
        assertNull(ContentParser.normalizePhone("927771234", null))
        assertIs<ScanContent.Text>(parse("927771234", countryCode = null))
    }

    @Test
    fun refusesGarbageAsPhone() {
        assertNull(ContentParser.normalizePhone("no digits here", "992"))
        assertNull(ContentParser.normalizePhone("12", "992"), "слишком короткий")
        assertNull(ContentParser.normalizePhone("+9929277712345678901", "992"), "слишком длинный")
        assertNull(ContentParser.normalizePhone("992+927771234", "992"), "плюс не в начале")
    }

    @Test
    fun parsesWifiCredentials() {
        val content = parse("WIFI:T:WPA;S:Guest Wifi;P:secret123;H:true;;")

        assertIs<ScanContent.Wifi>(content)
        assertEquals("Guest Wifi", content.ssid)
        assertEquals("secret123", content.password)
        assertEquals(ScanContent.Wifi.Security.Wpa, content.security)
        assertTrue(content.hidden)
    }

    @Test
    fun keepsEscapedSeparatorsInsideWifiPassword() {
        // Пароль с точкой с запятой без обработки экранирования разъехался бы на два поля.
        val content = parse("""WIFI:T:WPA;S:Home;P:pa\;ss\:word;;""")

        assertIs<ScanContent.Wifi>(content)
        assertEquals("pa;ss:word", content.password)
    }

    @Test
    fun treatsOpenNetworkWithoutPassword() {
        val content = parse("WIFI:S:FreeSpot;;")

        assertIs<ScanContent.Wifi>(content)
        assertEquals(ScanContent.Wifi.Security.Open, content.security)
        assertNull(content.password)
        assertTrue(!content.hidden)
    }

    @Test
    fun parsesMeCardWithSeveralPhones() {
        val content = parse("MECARD:N:Сафаров,Сабурджон;TEL:+992927771234;TEL:927771235;EMAIL:s@sshorg.com;;")

        assertIs<ScanContent.Contact>(content)
        assertEquals("Сабурджон Сафаров", content.name)
        assertEquals(listOf("+992927771234", "+992927771235"), content.phones)
        assertEquals("s@sshorg.com", content.email)
    }

    @Test
    fun parsesVCard() {
        val content = parse(
            """
            BEGIN:VCARD
            VERSION:3.0
            FN:Сабурджон Сафаров
            TEL;TYPE=CELL:+992927771234
            EMAIL:s@sshorg.com
            END:VCARD
            """.trimIndent(),
        )

        assertIs<ScanContent.Contact>(content)
        assertEquals("Сабурджон Сафаров", content.name)
        assertEquals(listOf("+992927771234"), content.phones)
        assertEquals("s@sshorg.com", content.email)
    }

    @Test
    fun fallsBackToText() {
        assertIs<ScanContent.Text>(parse("просто заметка"))
    }
}

class DetectorPlannerTest {

    @Test
    fun buildsOneDecoderForSeveralTargetsSharingIt() {
        val plan = DetectorPlanner.plan(
            setOf(ScanTarget.QrCode, ScanTarget.PhoneNumber, ScanTarget.Wifi, ScanTarget.Url),
        )

        assertEquals(setOf(DecoderKind.Qr), plan, "четыре цели обслуживает один декодер")
    }

    @Test
    fun singleTargetStillBuildsItsDecoder() {
        assertEquals(setOf(DecoderKind.Qr), DetectorPlanner.plan(setOf(ScanTarget.PhoneNumber)))
    }
}

class ScannerConfigTest {

    @Test
    fun rejectsEmptyTargets() {
        assertFailsWith<IllegalArgumentException> { ScannerConfig(targets = emptySet()) }
    }

    @Test
    fun rejectsNonDigitCountryCode() {
        assertFailsWith<IllegalArgumentException> { ScannerConfig(defaultCountryCode = "+992") }
    }
}
