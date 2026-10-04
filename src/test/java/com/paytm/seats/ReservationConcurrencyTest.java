package com.paytm.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.paytm.seats.ApiClient.Response;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReservationConcurrencyTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(System.getProperty("test.postgres.image", "postgres:16-alpine"))
            .withCommand("postgres", "-c", "max_connections=200");

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    ApiClient api;

    @BeforeEach
    void setUp() {
        api = new ApiClient("http://localhost:" + port);
    }

    private static List<String> seats(int n) {
        return IntStream.rangeClosed(1, n).mapToObj(i -> "A" + i).toList();
    }

    /** Fires all tasks at once behind a start gate and returns their results in order. */
    private static List<Response> storm(int n, IntFunction<Response> task) throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Response>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    return task.apply(idx);
                }));
            }
            gate.countDown();
            List<Response> out = new ArrayList<>();
            for (Future<Response> f : futures) {
                out.add(f.get());
            }
            return out;
        }
    }

    private static long count(List<Response> rs, int status) {
        return rs.stream().filter(r -> r.status() == status).count();
    }

    private static long count(List<Response> rs, int status, String reason) {
        return rs.stream().filter(r -> r.status() == status && reason.equals(r.reason())).count();
    }

    private void assertReconciled(String showId) {
        JsonNode counts = api.show(showId).body().get("counts");
        assertThat(counts.get("available").asLong() + counts.get("held").asLong() + counts.get("confirmed").asLong())
                .isEqualTo(counts.get("total").asLong());
    }

    @Test
    void hotSeatHasExactlyOneWinner() throws Exception {
        String showId = api.createShow(seats(20), 25000, 4).get("id").asText();
        int users = 300;
        List<String> tokens = IntStream.range(0, users).mapToObj(i -> api.token("hot-" + i)).toList();

        List<Response> rs = storm(users, i -> api.reserve(tokens.get(i), showId, List.of("A12"), "k-" + i));

        assertThat(count(rs, 201)).isEqualTo(1);
        assertThat(count(rs, 409, "seat_taken")).isEqualTo(users - 1);
        assertThat(rs).noneMatch(r -> r.status() >= 500);
        JsonNode show = api.show(showId).body();
        assertThat(show.get("counts").get("confirmed").asLong()).isEqualTo(1);
        assertReconciled(showId);
    }

    @Test
    void overlappingMultiSeatRequestsAreAllOrNothingAndDeadlockFree() throws Exception {
        String showId = api.createShow(seats(8), 10000, 4).get("id").asText();
        int users = 200;
        List<String> tokens = IntStream.range(0, users).mapToObj(i -> api.token("multi-" + i)).toList();
        List<String> hot = seats(6);

        List<Response> rs = storm(users, i -> {
            List<String> pick = new ArrayList<>(hot);
            Collections.shuffle(pick, ThreadLocalRandom.current());
            return api.reserve(tokens.get(i), showId, pick.subList(0, 2 + (i % 2)), "k-" + i);
        });

        assertThat(rs).noneMatch(r -> r.status() >= 500);
        Map<String, String> owner = new HashMap<>();
        long seatsWon = 0;
        for (Response r : rs.stream().filter(r -> r.status() == 201).toList()) {
            String rid = r.body().get("reservation_id").asText();
            for (JsonNode s : r.body().get("seats")) {
                assertThat(owner.put(s.asText(), rid)).as("seat %s sold twice", s.asText()).isNull();
                seatsWon++;
            }
            Integer owned = jdbc.queryForObject("SELECT count(*) FROM seats WHERE reservation_id = ?::uuid",
                    Integer.class, rid);
            assertThat(owned).as("all-or-nothing").isEqualTo(r.body().get("seats").size());
        }
        assertThat(api.show(showId).body().get("counts").get("confirmed").asLong()).isEqualTo(seatsWon);
        assertReconciled(showId);
    }

    @Test
    void perUserLimitHoldsUnderParallelRequests() throws Exception {
        String showId = api.createShow(seats(30), 10000, 4).get("id").asText();
        String token = api.token("greedy");

        List<Response> rs = storm(10, i -> api.reserve(token, showId, List.of("A" + (i + 1)), "g-" + i));

        assertThat(count(rs, 201)).isEqualTo(4);
        assertThat(count(rs, 409, "per_user_limit")).isEqualTo(6);
        Integer held = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE show_id = ?::uuid AND user_id = 'greedy'", Integer.class, showId);
        assertThat(held).isEqualTo(4);

        Response tooMany = api.reserve(api.token("bulk"), showId, seats(5).stream().map(s -> "A2" + s.substring(1)).toList(), "b");
        assertThat(tooMany.status()).isEqualTo(409);
        assertThat(tooMany.reason()).isEqualTo("per_user_limit");
    }

    @Test
    void sameKeyReservesExactlyOnceAndRejectsDifferentBody() throws Exception {
        String showId = api.createShow(seats(10), 25000, 4).get("id").asText();
        String token = api.token("retrier");

        List<Response> rs = storm(30, i -> api.reserve(token, showId, List.of("A3", "A4"), "same-key"));

        assertThat(count(rs, 201)).isEqualTo(1);
        assertThat(count(rs, 200)).isEqualTo(29);
        assertThat(rs.stream().map(r -> r.body().get("reservation_id").asText()).distinct()).hasSize(1);
        assertThat(rs.stream().filter(r -> r.status() == 200)).allMatch(r -> "true".equals(r.header("Idempotent-Replayed")));
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM reservations WHERE show_id = ?::uuid AND user_id = 'retrier'", Integer.class, showId);
        assertThat(rows).isEqualTo(1);
        assertThat(api.show(showId).body().get("counts").get("confirmed").asLong()).isEqualTo(2);

        Response reordered = api.reserve(token, showId, List.of("A4", "A3"), "same-key");
        assertThat(reordered.status()).isEqualTo(200);
        Response different = api.reserve(token, showId, List.of("A5"), "same-key");
        assertThat(different.status()).isEqualTo(409);
        assertThat(different.reason()).isEqualTo("idempotency_key_reused");
        assertThat(api.show(showId).body().get("counts").get("confirmed").asLong()).isEqualTo(2);
    }

    @Test
    void identityComesFromTokenAndOnlyOwnerCanCancel() {
        String showId = api.createShow(seats(5), 25000, 4).get("id").asText();
        String alice = api.token("alice");
        String mallory = api.token("mallory");

        Response spoof = api.reserveRaw(mallory, showId,
                Map.of("seats", List.of("A1"), "idempotency_key", "s1", "user_id", "alice"));
        assertThat(spoof.status()).isEqualTo(201);
        assertThat(spoof.body().get("user_id").asText()).isEqualTo("mallory");

        Response mine = api.reserve(alice, showId, List.of("A2"), "a1");
        String rid = mine.body().get("reservation_id").asText();
        assertThat(api.cancel(mallory, rid).status()).isEqualTo(404);
        assertThat(api.show(showId).body().get("counts").get("confirmed").asLong()).isEqualTo(2);

        assertThat(api.cancel(alice, rid).body().get("status").asText()).isEqualTo("cancelled");
        Response rebook = api.reserve(mallory, showId, List.of("A2"), "s2");
        assertThat(rebook.status()).isEqualTo(201);

        // A repeated cancel by the previous owner must not resurrect the seat now confirmed to mallory.
        assertThat(api.cancel(alice, rid).status()).isEqualTo(200);
        String a2Owner = jdbc.queryForObject("SELECT user_id FROM seats WHERE show_id = ?::uuid AND label = 'A2'",
                String.class, showId);
        assertThat(a2Owner).isEqualTo("mallory");
        assertReconciled(showId);
    }

    @Test
    void cancelReturnsQuotaAndSeatIsRebookable() {
        String showId = api.createShow(seats(10), 25000, 2).get("id").asText();
        String bob = api.token("bob");
        String r1 = api.reserve(bob, showId, List.of("A1", "A2"), "b1").body().get("reservation_id").asText();
        assertThat(api.reserve(bob, showId, List.of("A3"), "b2").reason()).isEqualTo("per_user_limit");

        api.cancel(bob, r1);
        assertThat(api.reserve(bob, showId, List.of("A3"), "b3").status()).isEqualTo(201);
        assertThat(api.reserve(api.token("carol"), showId, List.of("A1"), "c1").status()).isEqualTo(201);
    }

    @Test
    void invariantHoldsDuringBurst() throws Exception {
        String showId = api.createShow(seats(50), 10000, 4).get("id").asText();
        int n = 400;
        List<String> tokens = IntStream.range(0, 100).mapToObj(i -> api.token("burst-" + i)).toList();
        AtomicBoolean done = new AtomicBoolean();
        ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
        Thread poller = Thread.ofVirtual().start(() -> {
            while (!done.get()) {
                JsonNode body = api.show(showId).body();
                if (!body.get("reconciled").asBoolean()) {
                    violations.add(body.get("counts").toString());
                }
            }
        });

        List<Response> rs = storm(n, i -> {
            int a = ThreadLocalRandom.current().nextInt(1, 51);
            return api.reserve(tokens.get(i % 100), showId, List.of("A" + a), UUID.randomUUID().toString());
        });
        done.set(true);
        poller.join();

        assertThat(violations).isEmpty();
        assertThat(rs).noneMatch(r -> r.status() >= 500);
        Set<String> sold = new HashSet<>();
        rs.stream().filter(r -> r.status() == 201)
                .forEach(r -> assertThat(sold.add(r.body().get("seats").get(0).asText())).isTrue());
        assertThat(api.show(showId).body().get("counts").get("confirmed").asLong()).isEqualTo(sold.size());
        assertReconciled(showId);
    }

    @Test
    void fractionalPricesAndLimitsAreRejectedWithoutCreatingShows() {
        Integer before = jdbc.queryForObject("SELECT count(*) FROM shows", Integer.class);
        for (Number price : List.of(25000.75, 25000.0)) {
            Response r = api.post("/shows", null,
                    Map.of("name", "decimal", "seats", List.of("A1"), "price_paise", price),
                    Map.of("X-Admin-Key", "dev-admin-key"));
            assertThat(r.status()).isEqualTo(400);
        }
        Response limit = api.post("/shows", null,
                Map.of("name", "decimal-limit", "seats", List.of("A1"), "price_paise", 25000, "per_user_limit", 4.5),
                Map.of("X-Admin-Key", "dev-admin-key"));
        assertThat(limit.status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shows", Integer.class)).isEqualTo(before);
    }

    @Test
    void readinessUsesActiveDatabaseAndRecoversAfterAnOutage() throws Exception {
        assertThat(api.get("/readyz", null).status()).isEqualTo(200);
        // Pause only this test's disposable database. Existing pooled sockets stay open,
        // so this also proves that readiness performs a real query with a bounded timeout.
        postgres.getDockerClient().pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            Thread.sleep(1100); // expire the health indicator's one-second cache
            long start = System.nanoTime();
            assertThat(api.get("/readyz", null).status()).isEqualTo(503);
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(6000);
            assertThat(api.get("/livez", null).status()).isEqualTo(200);
        } finally {
            postgres.getDockerClient().unpauseContainerCmd(postgres.getContainerId()).exec();
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (api.get("/readyz", null).status() != 200 && System.nanoTime() < deadline) {
            Thread.sleep(200);
        }
        assertThat(api.get("/readyz", null).status()).isEqualTo(200);
        assertReconciled(api.createShow(seats(3), 25000, 4).get("id").asText());
    }

    @Test
    void validationAndAuthAreCleanFourHundreds() {
        String showId = api.createShow(seats(3), 100, 4).get("id").asText();
        String t = api.token("val");
        assertThat(api.reserve(t, showId, List.of("Z99"), "v1").status()).isEqualTo(400);
        assertThat(api.reserve(t, showId, List.of("A1", "A1"), "v2").status()).isEqualTo(400);
        assertThat(api.reserveRaw(t, showId, Map.of("seats", List.of("A1"))).status()).isEqualTo(400);
        assertThat(api.reserve(null, showId, List.of("A1"), "v3").status()).isEqualTo(401);
        assertThat(api.reserve("not-a-jwt", showId, List.of("A1"), "v4").status()).isEqualTo(401);
        assertThat(api.reserve(t, UUID.randomUUID().toString(), List.of("A1"), "v5").status()).isEqualTo(404);
        assertThat(api.post("/shows", t, Map.of("name", "x", "seats", List.of("A1"), "price_paise", 1), Map.of()).status())
                .isEqualTo(403);
        assertThat(api.get("/readyz", null).status()).isEqualTo(200);
        assertThat(api.get("/metrics", null).status()).isEqualTo(200);
    }
}
