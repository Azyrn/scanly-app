package com.skeler.scanely.core.barcode

import android.net.Uri
import com.skeler.scanely.R
import com.skeler.scanely.core.actions.ScanAction
import com.skeler.scanely.core.actions.WifiType

// Mirrors ML Kit's structured-value parsing so both engines yield identical actions.
object BarcodeContentParser {

    /** Payload prefixes (case-insensitive) in the order they are tried, each with its parser. */
    private val PREFIX_PARSERS: List<Pair<String, (String, MutableList<ScanAction>) -> Unit>> = listOf(
        "WIFI:" to ::parseWifi,
        "http://" to { text, actions -> actions.add(ScanAction.OpenUrl(text)) },
        "https://" to { text, actions -> actions.add(ScanAction.OpenUrl(text)) },
        "www." to { text, actions -> actions.add(ScanAction.OpenUrl("https://$text")) },
        "mailto:" to ::parseMailto,
        "MATMSG:" to ::parseMatmsg,
        "tel:" to { text, actions -> actions.add(ScanAction.CallPhone(text.substring("tel:".length).trim())) },
        "smsto:" to ::parseSms,
        "sms:" to ::parseSms,
        "BEGIN:VCARD" to ::parseVCard,
        "MECARD:" to ::parseMeCard,
        "geo:" to { text, actions -> actions.add(ScanAction.OpenUrl(text)) },
    )

    fun parse(barcode: DecodedBarcode): List<ScanAction> {
        val text = barcode.text
        val actions = mutableListOf<ScanAction>()

        val parser = PREFIX_PARSERS.firstOrNull { (prefix, _) -> text.startsWith(prefix, ignoreCase = true) }?.second
        when {
            parser != null -> parser(text, actions)

            text.contains("BEGIN:VEVENT", ignoreCase = true) -> parseVEvent(text, actions)

            else -> {
                if (barcode.isProductCode || isIsbn10(text)) {
                    actions.add(ScanAction.LookupProduct(text))
                }
                actions.add(ScanAction.ShowRaw(text))
            }
        }

        if (text.isNotBlank() && actions.none { it is ScanAction.CopyText }) {
            actions.add(ScanAction.CopyText(text, R.string.act_copy_barcode))
        }
        return actions
    }

    private fun parseWifi(text: String, actions: MutableList<ScanAction>) {
        val fields = splitQrFields(text.substring("WIFI:".length))
        val ssid = fields["S"]
        if (ssid.isNullOrEmpty()) {
            actions.add(ScanAction.ShowRaw(text))
            return
        }
        val wifiType = when (fields["T"]?.uppercase()) {
            "WPA", "WPA2", "WPA3", "SAE" -> WifiType.WPA
            "WEP" -> WifiType.WEP
            else -> WifiType.OPEN
        }
        actions.add(
            ScanAction.ConnectWifi(
                ssid = ssid,
                password = fields["P"]?.takeIf { it.isNotEmpty() },
                type = wifiType
            )
        )
        actions.add(ScanAction.CopyText(ssid, R.string.act_copy_ssid))
    }

    private fun parseMailto(text: String, actions: MutableList<ScanAction>) {
        val address = Uri.decode(text.substring("mailto:".length).substringBefore('?'))
        if (address.isBlank()) {
            actions.add(ScanAction.ShowRaw(text))
            return
        }
        var subject: String? = null
        var body: String? = null
        text.substringAfter('?', "").split('&').forEach { param ->
            val value = Uri.decode(param.substringAfter('=', ""))
            when (param.substringBefore('=').lowercase()) {
                "subject" -> subject = value
                "body" -> body = value
            }
        }
        actions.add(ScanAction.SendEmail(address, subject, body))
    }

    private fun parseMatmsg(text: String, actions: MutableList<ScanAction>) {
        val fields = splitQrFields(text.substring("MATMSG:".length))
        val to = fields["TO"]
        if (to.isNullOrBlank()) {
            actions.add(ScanAction.ShowRaw(text))
            return
        }
        actions.add(ScanAction.SendEmail(to, fields["SUB"], fields["BODY"]))
    }

    private fun parseSms(text: String, actions: MutableList<ScanAction>) {
        val rest = text.substringAfter(':')
        val number: String
        val message: String?
        if (text.startsWith("smsto:", ignoreCase = true)) {
            number = rest.substringBefore(':').trim()
            message = rest.substringAfter(':', "").takeIf { it.isNotEmpty() }
        } else {
            number = rest.substringBefore('?').trim()
            message = text.substringAfter("body=", "")
                .substringBefore('&')
                .takeIf { it.isNotEmpty() }
                ?.let { Uri.decode(it) }
        }
        if (number.isBlank()) {
            actions.add(ScanAction.ShowRaw(text))
            return
        }
        actions.add(ScanAction.SendSms(number, message))
    }

    private fun parseVCard(text: String, actions: MutableList<ScanAction>) {
        val properties = contentLines(text).toList()
        fun first(key: String) = properties.firstOrNull { it.first == key }?.second
        fun last(key: String) = properties.lastOrNull { it.first == key }?.second
        // Any FN wins (the last one); otherwise the first N, "last;first;..." read back to front.
        val name = last("FN") ?: first("N")?.let { n ->
            n.split(';').filter { it.isNotBlank() }.reversed().joinToString(" ")
        }
        val phone = first("TEL")
        val email = first("EMAIL")
        val org = last("ORG")?.substringBefore(';')
        if (name == null && phone == null && email == null) {
            actions.add(ScanAction.ShowRaw(text))
            return
        }
        actions.add(ScanAction.AddContact(name, phone, email, org))
    }

    private fun parseMeCard(text: String, actions: MutableList<ScanAction>) {
        val fields = splitQrFields(text.substring("MECARD:".length))
        // MECARD N is "last,first"
        val name = fields["N"]?.split(',')?.filter { it.isNotBlank() }?.let { parts ->
            if (parts.size >= 2) "${parts[1].trim()} ${parts[0].trim()}" else parts.joinToString(" ")
        }
        val phone = fields["TEL"]
        val email = fields["EMAIL"]
        if (name == null && phone == null && email == null) {
            actions.add(ScanAction.ShowRaw(text))
            return
        }
        actions.add(ScanAction.AddContact(name, phone, email, fields["ORG"]))
    }

    private fun parseVEvent(text: String, actions: MutableList<ScanAction>) {
        // The last occurrence of each property wins.
        val properties = contentLines(text).associate { it }
        actions.add(
            ScanAction.AddEvent(
                title = properties["SUMMARY"],
                location = properties["LOCATION"],
                description = properties["DESCRIPTION"],
                startRaw = properties["DTSTART"],
                endRaw = properties["DTEND"],
            )
        )
    }
}

/** vCard/iCalendar `KEY;PARAMS:value` lines as (upper-case key, value), skipping empty values. */
private fun contentLines(text: String): Sequence<Pair<String, String>> =
    text.lineSequence().mapNotNull { line ->
        val idx = line.indexOf(':')
        if (idx <= 0) return@mapNotNull null
        val key = line.take(idx).substringBefore(';').uppercase().trim()
        val value = line.substring(idx + 1).trim()
        if (value.isEmpty()) null else key to value
    }

// Splits "K:V;K:V;;" QR payloads honoring backslash escapes; first occurrence wins.
private fun splitQrFields(body: String): Map<String, String> {
    val fields = mutableMapOf<String, String>()
    for (part in splitUnescaped(body)) {
        val idx = part.indexOf(':')
        if (idx > 0) {
            val key = part.take(idx).uppercase().trim()
            if (key !in fields) fields[key] = part.substring(idx + 1)
        }
    }
    return fields
}

/** [body] split on `;`, with a backslash taking the next character literally. */
private fun splitUnescaped(body: String): List<String> {
    val parts = mutableListOf<String>()
    val sb = StringBuilder()
    var i = 0
    while (i < body.length) {
        val c = body[i]
        when {
            c == '\\' && i + 1 < body.length -> {
                sb.append(body[i + 1])
                i++
            }
            c == ';' -> {
                parts.add(sb.toString())
                sb.clear()
            }
            else -> sb.append(c)
        }
        i++
    }
    if (sb.isNotEmpty()) parts.add(sb.toString())
    return parts
}

private const val ISBN10_LENGTH = 10

private fun isIsbn10(value: String): Boolean {
    if (value.length != ISBN10_LENGTH) return false
    // Nine digits, then a check character that may be X.
    if (!value.dropLast(1).all { it.isDigit() }) return false
    val last = value.last()
    return last.isDigit() || last == 'X' || last == 'x'
}
