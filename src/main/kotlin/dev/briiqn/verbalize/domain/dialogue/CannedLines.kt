package dev.briiqn.verbalize.domain.dialogue

object CannedLines {
    private val lines = listOf(
        "Ah, a new operator! Mind the steam vents, they bite.",
        "The brass gears turn for no one, friend, yet here we are.",
        "I heard the foundry whistle twice today. Twice! Strange omens.",
        "Bring me copper rivets and I shall tell you of the old aqueduct.",
        "The smog rolls in from the east. Keep your goggles polished.",
        "A fine day for tinkering, would you not agree?"
    )

    fun random(): String = lines.random()

    fun reactionTo(heard: String): String =
        if (heard.isNotBlank()) "You say \"${heard.take(80)}\"? ${random()}" else random()

    private val smileReactions = listOf(
        "A smile! The boilers must be running kind today.",
        "Grinning like a freshly polished kettle, you are.",
        "Careful with that grin, you'll fog your goggles.",
        "Someone's gears are turning smooth this morning."
    )
    private val frownReactions = listOf(
        "Oi, who soured your oil then?",
        "That scowl could curdle steam, traveller.",
        "Storm clouds on your face and in the sky both, eh?",
        "Easy now — whatever broke, the forge can mend it."
    )
    private val surpriseReactions = listOf(
        "Seen a ghost in the smog, have you?",
        "Eyes like pressure gauges at the red line!",
        "What startled you? If it hisses, walk away slowly.",
        "Steady on — you look like the whistle blew early."
    )

    fun expressionReaction(faceState: Int): String = when (faceState) {
        4 -> smileReactions.random()
        5 -> frownReactions.random()
        3 -> surpriseReactions.random()
        else -> random()
    }

    fun expressionDescription(faceState: Int): String = when (faceState) {
        4 -> "with a big smile"
        5 -> "with an angry frown"
        3 -> "looking shocked"
        else -> "blankly"
    }
}
