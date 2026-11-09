package com.screenshotbrain

/** Rule-based classifier. Returns (type, detail). Swap for an on-device model later. */
object Extractor {
    private val money = Regex("""[$€£]\s?\d{1,6}(?:[.,]\d{2})""")
    private val otp = Regex("""\b\d{4,8}\b""")
    private val tracking = Regex("""\b1Z[0-9A-Z]{16}\b|\b\d{12,22}\b""")
    private val flight = Regex("""\b[A-Z]{2}\s?\d{2,4}\b""")
    private val date = Regex(
        """\b\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}\b""",
        RegexOption.IGNORE_CASE
    )
    private val address = Regex(
        """\b\d{1,5}\s+[A-Za-z0-9.\s]{3,30}\s(?:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr)\b""",
        RegexOption.IGNORE_CASE
    )
    private val wifi = Regex("""password\W+(\S+)""", RegexOption.IGNORE_CASE)

    private fun has(l: String, vararg k: String) = k.any { it in l }

    fun classify(t: String): Pair<String, String> {
        val l = t.lowercase()
        if (has(l, "verification", "one-time", "otp", "security code", "your code")) {
            otp.find(t)?.let { return "Code" to it.value }
        }
        if (has(l, "track", "shipped", "deliver", "package", "order")) {
            tracking.find(t)?.let { return "Package" to it.value }
        }
        if (has(l, "boarding", "flight", "gate", "departure")) {
            return "Flight" to (flight.find(t)?.value ?: "")
        }
        if (has(l, "total", "subtotal", "receipt", "amount due", "tax")) {
            val m = money.findAll(t).lastOrNull()
            if (m != null) return "Receipt" to m.value
        }
        if (has(l, "meeting", "invite", "rsvp", "appointment", "reservation", "tickets", "concert")) {
            date.find(t)?.let { return "Event" to it.value }
        }
        address.find(t)?.let { return "Address" to it.value.trim() }
        wifi.find(t)?.let { return "Wi-Fi" to it.groupValues[1] }
        return "Note" to ""
    }
}
