package com.ssh.scanner

/**
 * Что оказалось внутри кода.
 *
 * Потребителю почти никогда не нужна сырая строка: экрану оплаты нужен номер, экрану Wi-Fi — пароль.
 * Разбор живёт здесь, а не у каждого приложения заново, потому что форматы фиксированные,
 * а ошибаются в них одинаково.
 */
public sealed interface ScanContent {

    /** Исходная строка кода — на случай, если потребителю нужно именно она. */
    public val raw: String

    public data class Url(override val raw: String, val address: String) : ScanContent

    /** Номер в формате E.164 (`+992927771234`), если его удалось привести. */
    public data class Phone(override val raw: String, val e164: String) : ScanContent

    public data class Wifi(
        override val raw: String,
        val ssid: String,
        val password: String?,
        val security: Security,
        val hidden: Boolean,
    ) : ScanContent {
        public enum class Security { Open, Wep, Wpa, Sae }
    }

    public data class Contact(
        override val raw: String,
        val name: String?,
        val phones: List<String>,
        val email: String?,
    ) : ScanContent

    /** Всё остальное. */
    public data class Text(override val raw: String) : ScanContent
}

/** Разбирает строку кода в типизированное содержимое. */
internal object ContentParser {

    fun parse(raw: String, defaultCountryCode: String?): ScanContent {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("WIFI:", ignoreCase = true) -> parseWifi(trimmed)
            trimmed.startsWith("MECARD:", ignoreCase = true) -> parseMeCard(trimmed, defaultCountryCode)
            trimmed.startsWith("BEGIN:VCARD", ignoreCase = true) -> parseVCard(trimmed, defaultCountryCode)
            trimmed.startsWith("tel:", ignoreCase = true) ->
                phoneOrText(trimmed, trimmed.substring(4), defaultCountryCode)

            isUrl(trimmed) -> ScanContent.Url(raw = trimmed, address = trimmed)
            else -> phoneOrText(trimmed, trimmed, defaultCountryCode)
        }
    }

    private fun isUrl(text: String): Boolean =
        text.startsWith("http://", ignoreCase = true) ||
            text.startsWith("https://", ignoreCase = true)

    private fun phoneOrText(raw: String, candidate: String, defaultCountryCode: String?): ScanContent {
        val e164 = normalizePhone(candidate, defaultCountryCode)
        return if (e164 != null) ScanContent.Phone(raw = raw, e164 = e164) else ScanContent.Text(raw)
    }

    /**
     * Приводит номер к E.164.
     *
     * Реестр операторских префиксов намеренно не ведётся: он протухает быстрее, чем выходит релиз
     * библиотеки, а номер, отвергнутый из-за устаревшего списка, выглядит для пользователя
     * как поломка сканера.
     */
    fun normalizePhone(input: String, defaultCountryCode: String?): String? {
        val digitsOnly = input.filter { it.isDigit() || it == '+' }
        if (digitsOnly.isEmpty()) return null
        // Плюс имеет смысл только в начале: «+» посреди номера — это уже не номер.
        if (digitsOnly.indexOf('+') > 0) return null

        val hasPlus = digitsOnly.startsWith("+")
        val digits = digitsOnly.removePrefix("+")
        if (!digits.all { it.isDigit() }) return null

        val international = when {
            hasPlus -> digits
            digits.startsWith("00") -> digits.substring(2)
            defaultCountryCode != null && digits.length == NATIONAL_NUMBER_LENGTH ->
                defaultCountryCode + digits

            defaultCountryCode != null && digits.startsWith(defaultCountryCode) -> digits
            else -> return null
        }

        return if (international.length in MIN_E164_DIGITS..MAX_E164_DIGITS) {
            "+$international"
        } else {
            null
        }
    }

    private fun parseWifi(raw: String): ScanContent {
        val fields = splitEscapedFields(raw.substringAfter(':'))
        val ssid = fields["S"] ?: return ScanContent.Text(raw)
        val security = when (fields["T"]?.uppercase()) {
            "WEP" -> ScanContent.Wifi.Security.Wep
            "WPA", "WPA2", "WPA2-EAP" -> ScanContent.Wifi.Security.Wpa
            "SAE", "WPA3" -> ScanContent.Wifi.Security.Sae
            else -> ScanContent.Wifi.Security.Open
        }
        return ScanContent.Wifi(
            raw = raw,
            ssid = ssid,
            password = fields["P"]?.takeIf { it.isNotEmpty() },
            security = security,
            hidden = fields["H"].equals("true", ignoreCase = true),
        )
    }

    private fun parseMeCard(raw: String, defaultCountryCode: String?): ScanContent {
        val fields = splitEscapedFields(raw.substringAfter(':'), repeatable = setOf("TEL"))
        return ScanContent.Contact(
            raw = raw,
            // Имя в MECARD пишется как «Фамилия,Имя» — возвращаем в человеческом порядке.
            name = fields["N"]?.split(',')?.filter { it.isNotBlank() }?.reversed()?.joinToString(" "),
            phones = fields.all("TEL").mapNotNull { normalizePhone(it, defaultCountryCode) },
            email = fields["EMAIL"],
        )
    }

    private fun parseVCard(raw: String, defaultCountryCode: String?): ScanContent {
        var name: String? = null
        var email: String? = null
        val phones = mutableListOf<String>()

        for (line in raw.lines()) {
            val separator = line.indexOf(':')
            if (separator < 0) continue
            val property = line.substring(0, separator).uppercase()
            val value = line.substring(separator + 1).trim()

            when {
                property == "FN" -> name = value
                property.startsWith("TEL") ->
                    normalizePhone(value, defaultCountryCode)?.let { phones += it }

                property.startsWith("EMAIL") -> email = value
                property == "N" && name == null ->
                    name = value.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
            }
        }
        return ScanContent.Contact(raw = raw, name = name, phones = phones, email = email)
    }

    /**
     * Разбирает записи вида `S:имя;P:пароль;;`.
     * Точка с запятой и двоеточие внутри значений экранируются обратным слэшем — без обработки
     * экранирования пароль с `;` разъезжается на два поля.
     */
    private fun splitEscapedFields(
        text: String,
        repeatable: Set<String> = emptySet(),
    ): FieldMap {
        val single = mutableMapOf<String, String>()
        val repeated = mutableMapOf<String, MutableList<String>>()

        var key = StringBuilder()
        var value = StringBuilder()
        var readingKey = true
        var escaped = false

        fun flush() {
            val name = key.toString().uppercase()
            if (name.isNotEmpty()) {
                if (name in repeatable) {
                    repeated.getOrPut(name) { mutableListOf() } += value.toString()
                } else {
                    single[name] = value.toString()
                }
            }
            key = StringBuilder()
            value = StringBuilder()
            readingKey = true
        }

        for (symbol in text) {
            when {
                escaped -> {
                    (if (readingKey) key else value).append(symbol)
                    escaped = false
                }

                symbol == '\\' -> escaped = true
                symbol == ':' && readingKey -> readingKey = false
                symbol == ';' -> flush()
                else -> (if (readingKey) key else value).append(symbol)
            }
        }
        flush()
        return FieldMap(single, repeated)
    }

    private class FieldMap(
        private val single: Map<String, String>,
        private val repeated: Map<String, List<String>>,
    ) {
        operator fun get(key: String): String? = single[key] ?: repeated[key]?.firstOrNull()
        fun all(key: String): List<String> = repeated[key] ?: single[key]?.let { listOf(it) } ?: emptyList()
    }

    private const val NATIONAL_NUMBER_LENGTH = 9
    private const val MIN_E164_DIGITS = 8
    private const val MAX_E164_DIGITS = 15
}
