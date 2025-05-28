package me.diamondforge.kyromera.bot.services

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.freya022.botcommands.api.core.db.HikariSourceSupplier
import io.github.freya022.botcommands.api.core.service.annotations.BService
import io.github.oshai.kotlinlogging.KotlinLogging
import me.diamondforge.kyromera.bot.configuration.Config
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import java.sql.Connection
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val logger = KotlinLogging.logger { }

@BService
class DatabaseSource(config: Config) : HikariSourceSupplier {
    override val source = HikariDataSource(HikariConfig().apply {
        jdbcUrl = config.databaseConfig.url
        username = config.databaseConfig.user
        password = config.databaseConfig.password

        maximumPoolSize = 5
        schema = "public"
        minimumIdle = 5
        idleTimeout = 10.minutes.inWholeMilliseconds
        maxLifetime = 30.minutes.inWholeMilliseconds
        connectionTimeout = 30.seconds.inWholeMilliseconds
        leakDetectionThreshold = 10.seconds.inWholeMilliseconds

        connectionTestQuery = "SELECT 1"
        validationTimeout = 5.seconds.inWholeMilliseconds

        poolName = "KyromeraConnectionPool"

        addDataSourceProperty("cachePrepStmts", "true")
        addDataSourceProperty("prepStmtCacheSize", "250")
        addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
    })

    init {
        createFlyway("bc", "bc_database_scripts").migrate()
        createFlyway("public", "migrations").migrate()

        Database.connect(source)

        TransactionManager.manager.defaultIsolationLevel = Connection.TRANSACTION_READ_COMMITTED

        transaction {
            exec("SET search_path TO public")
        }

        logger.info { "Created database connection pool with HikariCP" }
    }

    private fun createFlyway(schema: String, scriptsLocation: String): Flyway = Flyway.configure()
        .dataSource(source)
        .schemas(schema)
        .locations(scriptsLocation)
        .validateMigrationNaming(true)
        .loggers("slf4j")
        .load()
}
