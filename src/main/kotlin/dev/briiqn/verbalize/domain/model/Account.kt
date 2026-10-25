package dev.briiqn.verbalize.domain.model

data class AvatarConfig(
    val hat: Int,
    val bodyColorHex: Int,
    val eyes: Int,
    val mouth: Int,
    val hair: Int
) {
    fun sanitized() = AvatarConfig(
        hat = hat.coerceIn(0, 4),
        bodyColorHex = bodyColorHex and 0xffffff,
        eyes = eyes.coerceIn(0, 2),
        mouth = mouth.coerceIn(0, 2),
        hair = hair.coerceIn(0, 2)
    )
}

data class Account(
    val username: String,
    val passwordHash: String,
    val avatar: AvatarConfig?,
    val reputation: Int = DEFAULT_REPUTATION
) {
    companion object {
        const val DEFAULT_REPUTATION = 100 // travellers arrive beloved; villagers sour only if you're rude
    }
}
