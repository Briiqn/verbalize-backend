package dev.briiqn.verbalize.infrastructure.db

import dev.briiqn.verbalize.domain.model.Account
import dev.briiqn.verbalize.domain.model.AvatarConfig
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** Jakarta Persistence mapping of the `accounts` table (Hibernate manages the schema). */
@Entity
@Table(name = "accounts")
class AccountEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0

    @Column(nullable = false, unique = true, length = 24)
    var username: String = ""

    @Column(name = "password_hash", nullable = false, length = 100)
    var passwordHash: String = ""

    @Column(name = "avatar_hat")
    var avatarHat: Int? = null

    @Column(name = "avatar_eyes")
    var avatarEyes: Int? = null

    @Column(name = "avatar_mouth")
    var avatarMouth: Int? = null

    @Column(name = "avatar_hair")
    var avatarHair: Int? = null

    @Column(name = "body_color")
    var bodyColor: Int? = null

    @Column(nullable = false)
    var reputation: Int = Account.DEFAULT_REPUTATION

    fun toAccount(): Account {
        val hat = avatarHat
        val avatar = if (hat != null) {
            AvatarConfig(
                hat = hat,
                bodyColorHex = bodyColor ?: 0,
                eyes = avatarEyes ?: 0,
                mouth = avatarMouth ?: 0,
                hair = avatarHair ?: 0
            )
        } else null
        return Account(username, passwordHash, avatar, reputation)
    }
}
