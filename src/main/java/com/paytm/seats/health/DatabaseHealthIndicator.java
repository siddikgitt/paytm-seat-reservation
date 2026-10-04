package com.paytm.seats.health;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Component;

/**
 * Readiness dependency check. Deliberately opens its own short-timeout connection instead of borrowing
 * from the pool: a saturated pool during a burst is not "DB down", and a dead DB must fail this check in
 * ~2s rather than after the pool's 30s borrow timeout. Results are cached briefly so probes stay cheap.
 * Any failure reports DOWN, so /readyz fails closed.
 */
@Component("database")
public class DatabaseHealthIndicator implements HealthIndicator {

    private static final long CACHE_MS = 1000;

    private final DataSourceProperties ds;
    private final AtomicReference<Cached> last = new AtomicReference<>();

    private record Cached(Health health, long atMillis) {
    }

    public DatabaseHealthIndicator(DataSourceProperties ds) {
        this.ds = ds;
    }

    @Override
    public Health health() {
        Cached cached = last.get();
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.atMillis() < CACHE_MS) {
            return cached.health();
        }
        Health health = check();
        last.set(new Cached(health, now));
        return health;
    }

    private Health check() {
        Properties p = new Properties();
        p.setProperty("user", ds.determineUsername());
        if (ds.determinePassword() != null) {
            p.setProperty("password", ds.determinePassword());
        }
        p.setProperty("connectTimeout", "2");
        p.setProperty("socketTimeout", "2");
        p.setProperty("loginTimeout", "2");
        long start = System.nanoTime();
        try (Connection c = DriverManager.getConnection(ds.determineUrl(), p);
             Statement st = c.createStatement()) {
            st.setQueryTimeout(2);
            try (ResultSet rs = st.executeQuery("SELECT 1")) {
                rs.next();
            }
            return Health.up().withDetail("latency_ms", (System.nanoTime() - start) / 1_000_000).build();
        } catch (Exception e) {
            return Health.down().withDetail("error", e.getClass().getSimpleName()).build();
        }
    }
}
