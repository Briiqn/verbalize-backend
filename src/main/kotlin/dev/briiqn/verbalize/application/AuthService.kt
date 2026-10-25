package dev.briiqn.verbalize.application

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.briiqn.verbalize.domain.model.Account
import dev.briiqn.verbalize.domain.ports.AccountRepository

sealed interface AuthOutcome {
    data class Success(val account: Account) : AuthOutcome
    data class Rejected(val reason: String) : AuthOutcome
}

/**
 * Login-or-register: unknown usernames are registered with the supplied password;
 * known usernames must present the matching password. Without a repository
 * (database offline) everyone is an ephemeral guest.
 */
class AuthService(private val repository: AccountRepository?) {
    fun authenticateOrRegister(usernameRaw: String, password: String): AuthOutcome {
        val username = usernameRaw.trim().take(24)
        if (username.isEmpty()) return AuthOutcome.Rejected("Enter an operator name.")
        if (repository == null) {
            return AuthOutcome.Success(Account(username, "", null))
        }
        if (password.length < 4) return AuthOutcome.Rejected("Password must be at least 4 characters.")
        val existing = repository.findByUsername(username)
        return if (existing == null) {
            val hash = BCrypt.withDefaults().hashToString(10, password.toCharArray())
            AuthOutcome.Success(repository.create(username, hash))
        } else {
            val verified = BCrypt.verifyer().verify(password.toCharArray(), existing.passwordHash).verified
            if (verified) AuthOutcome.Success(existing)
            else AuthOutcome.Rejected("Wrong password for '$username'.")
        }
    }

    /** Verify-only (REST): never registers new accounts. */
    fun verify(usernameRaw: String, password: String): AuthOutcome {
        val username = usernameRaw.trim().take(24)
        if (username.isEmpty()) return AuthOutcome.Rejected("missing username")
        if (repository == null) return AuthOutcome.Success(Account(username, "", null))
        val existing = repository.findByUsername(username)
            ?: return AuthOutcome.Rejected("unknown account — join the game once to register")
        val verified = BCrypt.verifyer().verify(password.toCharArray(), existing.passwordHash).verified
        return if (verified) AuthOutcome.Success(existing) else AuthOutcome.Rejected("wrong password")
    }
}
