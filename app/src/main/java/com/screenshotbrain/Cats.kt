package com.screenshotbrain

data class Cat(val name: String, val emoji: String, val color: Long)

object Cats {
    const val ALL = "*"

    val list = listOf(
        Cat("Code", "🔑", 0xFF7E57C2),
        Cat("Package", "📦", 0xFF8D6E63),
        Cat("Flight", "✈️", 0xFF29B6F6),
        Cat("Ticket", "🎫", 0xFFAB47BC),
        Cat("Receipt", "🧾", 0xFF43A047),
        Cat("Event", "📅", 0xFFFB8C00),
        Cat("Address", "📍", 0xFFE53935),
        Cat("Wi-Fi", "📶", 0xFF00897B),
        Cat("Link", "🔗", 0xFF3949AB),
        Cat("Chat", "💬", 0xFF039BE5),
        Cat("Social", "📱", 0xFFEC407A),
        Cat("Recipe", "🍳", 0xFFF4511E),
        Cat("Quote", "💭", 0xFF6D4C41),
        Cat("Note", "📝", 0xFF607D8B)
    )

    fun find(name: String): Cat? = list.firstOrNull { it.name == name }
}
