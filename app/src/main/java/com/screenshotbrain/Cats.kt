package com.screenshotbrain

data class Cat(val name: String, val color: Long)

object Cats {
    const val ALL = "*"

    val list = listOf(
        Cat("Code", 0xFF8B7EC8),
        Cat("Package", 0xFFA1887F),
        Cat("Flight", 0xFF5BA4CF),
        Cat("Ticket", 0xFFB278C4),
        Cat("Receipt", 0xFF6BAA75),
        Cat("Event", 0xFFE0A04A),
        Cat("Address", 0xFFD8756F),
        Cat("Wi-Fi", 0xFF5AA9A0),
        Cat("Link", 0xFF7585C8),
        Cat("Chat", 0xFF5BA8D9),
        Cat("Social", 0xFFD77AA0),
        Cat("Recipe", 0xFFE08560),
        Cat("Quote", 0xFF9C8071),
        Cat("Note", 0xFF9AA1A6)
    )

    fun find(name: String): Cat? = list.firstOrNull { it.name == name }
}
