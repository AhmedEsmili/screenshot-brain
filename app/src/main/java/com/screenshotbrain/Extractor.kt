package com.screenshotbrain

import java.util.concurrent.ConcurrentHashMap

/** Score-based classifier. Returns (type, detail). Highest score wins; nothing strong -> Note. */
object Extractor {
    private val money = Regex("""[$€£]\s?\d[\d,]*(?:\.\d{2})?""")
    private val otp = Regex("""\b\d{4,8}\b""")
    private val tracking = Regex("""\b1Z[0-9A-Z]{16}\b|\b\d{12,22}\b""")
    private val flight = Regex("""\b[A-Z]{2}\s?\d{2,4}\b""")
    private val url = Regex("""(?:https?://|www\.)[^\s]+""", RegexOption.IGNORE_CASE)
    private val time = Regex("""\b\d{1,2}:\d{2}\s?(?:am|pm)?\b""", RegexOption.IGNORE_CASE)
    private val handle = Regex("""@\w{2,}""")
    private val date = Regex(
        """\b\d{1,2}[/.-]\d{1,2}(?:[/.-]\d{2,4})?\b|\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\.?\s+\d{1,2}\b""",
        RegexOption.IGNORE_CASE
    )
    private val address = Regex(
        """\b\d{1,5}\s+[A-Za-z0-9.\s]{3,30}\s(?:street|st|avenue|ave|road|rd|boulevard|blvd|lane|ln|drive|dr)\b""",
        RegexOption.IGNORE_CASE
    )
    private val wifiPass = Regex("""password\W+(\S+)""", RegexOption.IGNORE_CASE)

    private val wordCache = ConcurrentHashMap<String, Regex>()
    private fun hits(l: String, vararg k: String) = k.count {
        wordCache.getOrPut(it) { Regex("\\b" + Regex.escape(it) + "\\b") }.containsMatchIn(l)
    }

    fun classify(t: String): Pair<String, String> {
        if (t.isBlank()) return "Note" to ""
        val l = t.lowercase()
        val lines = t.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val sc = mutableListOf<Triple<String, Int, String>>()
        fun add(n: String, s: Int, d: String = "") { if (s > 0) sc += Triple(n, s, d) }

        // Code (OTP)
        val ch = hits(l, "verification", "one-time", "one time", "otp", "security code", "your code", "passcode", "login code", "confirmation code")
        if (ch > 0) otp.find(t)?.let { add("Code", 4 + ch, it.value) }

        // Package
        val tr = tracking.find(t)
        val ph = hits(l, "tracking", "shipped", "delivery", "delivered", "parcel", "package", "courier", "out for delivery", "ups", "fedex", "dhl", "usps", "order")
        if (tr != null && ph >= 1) add("Package", 3 + ph, tr.value) else if (ph >= 3) add("Package", ph)

        // Flight
        val fh = hits(l, "boarding", "flight", "gate", "departure", "departs", "arrival", "arrives", "terminal", "seat", "pnr", "boarding pass", "airlines", "airways")
        if (fh >= 2) add("Flight", 2 + fh, flight.find(t)?.value ?: "")

        // Ticket
        val th = hits(l, "ticket", "admit one", "admission", "row", "section", "seat", "qr", "booking", "reference", "entry", "venue", "doors")
        if (th >= 3 && hits(l, "ticket", "admit", "admission") >= 1) add("Ticket", th)

        // Receipt
        val rh = hits(l, "total", "subtotal", "tax", "vat", "receipt", "invoice", "paid", "amount", "change", "cash", "visa", "mastercard", "qty", "thank you")
        val ms = money.findAll(t).toList()
        if (ms.isNotEmpty() && rh >= 2) {
            val totalLine = lines.lastOrNull { "total" in it.lowercase() && money.containsMatchIn(it) }
            val d = totalLine?.let { money.find(it)?.value } ?: ms.last().value
            add("Receipt", 2 + rh + minOf(ms.size, 3), d)
        }

        // Event
        val eh = hits(l, "meeting", "invite", "invitation", "rsvp", "appointment", "reservation", "concert", "webinar", "calendar", "join", "schedule", "starts", "party", "wedding")
        val dt = date.find(t)
        val tm = time.find(t)
        if (eh >= 1 && (dt != null || tm != null)) {
            add("Event", 2 + eh + (if (dt != null && tm != null) 1 else 0),
                listOfNotNull(dt?.value, tm?.value).joinToString(" "))
        }

        // Address
        address.find(t)?.let { add("Address", 4, it.value.trim()) }

        // Wi-Fi
        wifiPass.find(t)?.let {
            if (hits(l, "wifi", "wi-fi", "network", "ssid", "wireless") >= 1) add("Wi-Fi", 6, it.groupValues[1])
        }

        // Link
        url.find(t)?.let { add("Link", if (t.length < 250) 4 else 2, it.value.trimEnd('.', ',', ')')) }

        // Chat
        val ts = time.findAll(t).count()
        val app = hits(l, "whatsapp", "messenger", "telegram", "imessage", "signal", "delivered", "read", "typing", "online", "last seen", "message")
        var chat = 0
        if (ts >= 3) chat += 3
        chat += app
        if (lines.size >= 4 && ts >= 2 && lines.count { it.length < 45 } >= lines.size * 0.7) chat += 1
        if (chat >= 4) add("Chat", chat)

        // Social
        val hs = handle.findAll(t).count()
        val sh = hits(l, "likes", "followers", "following", "retweets", "reposts", "replies", "comments", "views", "subscribers", "reels")
        if (hs >= 1 && sh >= 2) add("Social", 3 + sh, handle.find(t)?.value ?: "")

        // Recipe
        val rc = hits(l, "ingredients", "tbsp", "tsp", "tablespoon", "teaspoon", "cup", "cups", "preheat", "bake", "simmer", "servings", "minutes", "oven", "chopped", "stir", "directions", "recipe", "grams")
        if (rc >= 4) add("Recipe", rc, lines.firstOrNull()?.take(40) ?: "")

        // Quote
        val quotes = t.count { it == '"' || it == '“' || it == '”' }
        if (t.length in 20..350 && quotes >= 2 && lines.size <= 8) add("Quote", 4)

        val best = sc.maxByOrNull { it.second }
        if (best != null) return best.first to best.third
        return "Note" to (lines.firstOrNull()?.take(40) ?: "")
    }
}
