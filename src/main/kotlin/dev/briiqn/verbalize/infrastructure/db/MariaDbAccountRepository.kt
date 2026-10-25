package dev.briiqn.verbalize.infrastructure.db

import dev.briiqn.verbalize.domain.model.Account
import dev.briiqn.verbalize.domain.model.AvatarConfig
import dev.briiqn.verbalize.domain.ports.AccountRepository
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.cfg.Configuration

/**
 * Account persistence via Jakarta Persistence (Hibernate ORM). Hibernate owns the
 * schema (hbm2ddl=update) and connection pooling runs through HikariCP, so there are
 * no hand-written SQL statements here — entities in, entities out.
 */
class MariaDbAccountRepository(url: String, user: String, password: String) : AccountRepository {
    private val emf: EntityManagerFactory

    init {
        val cfg = Configuration().apply {
            addAnnotatedClass(AccountEntity::class.java)
            setProperty("hibernate.connection.driver_class", "org.mariadb.jdbc.Driver")
            setProperty("hibernate.dialect", "org.hibernate.dialect.MariaDBDialect")
            setProperty("hibernate.connection.url", url)
            setProperty("hibernate.connection.username", user)
            setProperty("hibernate.connection.password", password)
            setProperty("hibernate.hbm2ddl.auto", "update")
            // pool through HikariCP, matching the previous raw-JDBC settings
            setProperty("hibernate.connection.provider_class", "org.hibernate.hikaricp.internal.HikariCPConnectionProvider")
            setProperty("hibernate.hikari.maximumPoolSize", "4")
            setProperty("hibernate.hikari.minimumIdle", "1")
            setProperty("hibernate.hikari.connectionTimeout", "5000")
            setProperty("hibernate.hikari.poolName", "verbalize-db")
        }
        emf = cfg.buildSessionFactory()
    }

    private fun <T> inTx(block: (EntityManager) -> T): T {
        val em = emf.createEntityManager()
        try {
            em.transaction.begin()
            val result = block(em)
            em.transaction.commit()
            return result
        } catch (e: Exception) {
            if (em.transaction.isActive) em.transaction.rollback()
            throw e
        } finally {
            em.close()
        }
    }

    private fun findEntity(em: EntityManager, username: String): AccountEntity? =
        em.createQuery("FROM AccountEntity WHERE username = :u", AccountEntity::class.java)
            .setParameter("u", username)
            .resultList
            .firstOrNull()

    override fun findByUsername(username: String): Account? =
        inTx { em -> findEntity(em, username)?.toAccount() }

    override fun create(username: String, passwordHash: String): Account =
        inTx { em ->
            val entity = AccountEntity().apply {
                this.username = username
                this.passwordHash = passwordHash
                this.reputation = Account.DEFAULT_REPUTATION
            }
            em.persist(entity)
            entity.toAccount()
        }

    override fun saveReputation(username: String, reputation: Int) {
        inTx { em -> findEntity(em, username)?.let { it.reputation = reputation } }
    }

    override fun saveAvatar(username: String, avatar: AvatarConfig) {
        inTx { em ->
            findEntity(em, username)?.apply {
                avatarHat = avatar.hat
                avatarEyes = avatar.eyes
                avatarMouth = avatar.mouth
                avatarHair = avatar.hair
                bodyColor = avatar.bodyColorHex
            }
        }
    }
}
