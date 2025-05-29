package app.soundcheck.feed.internal;

import app.soundcheck.shared.config.ConditionalOnRole;
import app.soundcheck.shared.config.Roles;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Keeps this instance's active ranking config up to date. One thread holds one database
 * connection of its own (outside the pool: it would otherwise occupy a pooled connection for ever) with
 * {@code LISTEN ranking_config_changed}, and reloads the config when a notification arrives. Whether or
 * not one arrives, it also reloads every 60 seconds: that is the fallback when the connection has
 * dropped, and what bounds how stale an instance can be.
 */
@Component
@ConditionalOnRole(Roles.API)
class RankingConfigListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(RankingConfigListener.class);
    static final int POLL_MILLIS = 60_000;

    private final RankingConfigs configs;
    private final DataSourceProperties database;
    private volatile boolean running;
    private volatile Thread thread;
    private volatile Connection connection;

    RankingConfigListener(RankingConfigs configs, DataSourceProperties database) {
        this.configs = configs;
        this.database = database;
    }

    @Override
    public void start() {
        running = true;
        thread = Thread.ofPlatform().daemon().name("ranking-config-listener").start(this::listen);
    }

    @Override
    public void stop() {
        running = false;
        // Closing the connection ends a blocked wait at once.
        closeQuietly(connection);
        Thread current = thread;
        if (current != null) {
            try {
                current.join(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void listen() {
        while (running) {
            try {
                Connection listening = connect();
                connection = listening;
                PGConnection postgres = listening.unwrap(PGConnection.class);
                while (running) {
                    // Blocks until a notification arrives or the poll interval has passed.
                    PGNotification[] notifications = postgres.getNotifications(POLL_MILLIS);
                    if (!running) {
                        break;
                    }
                    if (notifications != null && notifications.length > 0) {
                        log.info("ranking config change announced; reloading");
                    }
                    configs.reload();
                }
            } catch (SQLException | RuntimeException e) {
                if (running) {
                    log.warn("ranking config listener lost its connection ({}); retrying", e.toString());
                    reloadQuietly();
                    pause();
                }
            } finally {
                closeQuietly(connection);
            }
        }
    }

    private Connection connect() throws SQLException {
        Connection listening = DriverManager.getConnection(
                database.determineUrl(), database.determineUsername(), database.determinePassword());
        try (Statement statement = listening.createStatement()) {
            statement.execute("LISTEN " + RankingConfigRepository.CHANNEL);
        }
        return listening;
    }

    private void reloadQuietly() {
        try {
            configs.reload();
        } catch (RuntimeException e) {
            log.warn("could not reload the ranking config: {}", e.toString());
        }
    }

    private void pause() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                // nothing to do: it is being thrown away
            }
        }
    }
}
