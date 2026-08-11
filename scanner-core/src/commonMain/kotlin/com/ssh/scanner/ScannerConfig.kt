package com.ssh.scanner

/**
 * Что именно сканер должен искать в кадре.
 *
 * Цель — это не формат кода, а **намерение потребителя**: экрану «перевод по номеру» нужен телефон,
 * откуда бы он ни пришёл, и не нужен Wi-Fi-пароль, даже если он в кадре есть. Один формат кода
 * обслуживает несколько целей, поэтому набор целей превращается в набор декодеров планировщиком,
 * а не один к одному.
 */
public enum class ScanTarget {
    /** Любой QR-код, что бы в нём ни лежало. */
    QrCode,

    /** Ссылка. */
    Url,

    /** Телефонный номер: `tel:`, MECARD, vCard или просто номер строкой. */
    PhoneNumber,

    /** Учётные данные Wi-Fi (`WIFI:`). */
    Wifi,

    /** Контакт: MECARD или vCard. */
    Contact,
}

/** Сколько объектов возвращать с одного кадра. */
public enum class MultiScan {
    /** Только ближайший к центру кадра — обычное поведение сканера «наведи и держи». */
    Single,

    /** Все найденные: инвентаризация, несколько кодов на одном листе. */
    All,
}

/**
 * Настройки сканирования.
 *
 * Все переключатели — перечисления, а не флаги `Boolean`: `scan(image, true, false)` на месте вызова
 * не читается, а `MultiScan.All` читается.
 */
public data class ScannerConfig(
    val targets: Set<ScanTarget> = setOf(ScanTarget.QrCode),
    val multiScan: MultiScan = MultiScan.Single,
    /**
     * Код страны для номеров, записанных без международного префикса (`901234567`).
     * Не задан — такие номера остаются просто текстом: угадывать страну за пользователя нельзя,
     * один и тот же девятизначный номер в разных странах принадлежит разным людям.
     */
    val defaultCountryCode: String? = null,
) {
    init {
        require(targets.isNotEmpty()) { "Нужна хотя бы одна цель сканирования" }
        require(defaultCountryCode == null || defaultCountryCode.all { it.isDigit() }) {
            "Код страны состоит только из цифр, получено «$defaultCountryCode»"
        }
    }
}

/** Формат кода, из которого получен результат. */
public enum class ScanFormat {
    QrCode,
}

/** Декодеры, которые нужно построить для набора целей. */
internal enum class DecoderKind {
    Qr,
}

/**
 * Превращает набор целей в минимальный набор декодеров.
 *
 * Смысл существования: цели `QrCode`, `PhoneNumber` и `Wifi` обслуживаются одним декодером,
 * и строить его трижды — значит трижды разбирать каждый кадр.
 */
internal object DetectorPlanner {

    fun plan(targets: Set<ScanTarget>): Set<DecoderKind> = targets.mapTo(mutableSetOf()) { target ->
        when (target) {
            ScanTarget.QrCode,
            ScanTarget.Url,
            ScanTarget.PhoneNumber,
            ScanTarget.Wifi,
            ScanTarget.Contact,
            -> DecoderKind.Qr
        }
    }
}
