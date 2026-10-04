import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * On-sale stampede against a live deployment. Zero dependencies: run with `java scripts/Burst.java <BASE_URL>`.
 *
 * Phases: hot-seat storm (many users, one seat), multi hot-seat storm, general stampede with same-key
 * retries, idempotency race, per-user-limit race, identity spoofing and cancel/rebook. Prints the outcome
 * distribution and a final reconciliation against GET /shows/{id} and /metrics. Exits 1 on any violation.
 *
 * Env: ADMIN_KEY (dev-admin-key), CONCURRENCY (1000), STAMPEDE (20000), HOT_USERS (500), USERS (2000),
 *      ROWS (20), COLS (100), RETRY_PCT (10).
 */
public class Burst {

    static String base;
    static String adminKey = env("ADMIN_KEY", "dev-admin-key");
    static int concurrency = Integer.parseInt(env("CONCURRENCY", "1000"));
    static int stampede = Integer.parseInt(env("STAMPEDE", "20000"));
    static int hotUsers = Integer.parseInt(env("HOT_USERS", "500"));
    static int users = Integer.parseInt(env("USERS", "2000"));
    static int rows = Integer.parseInt(env("ROWS", "20"));
    static int cols = Integer.parseInt(env("COLS", "100"));
    static int retryPct = Integer.parseInt(env("RETRY_PCT", "10"));
    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);

    static final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();
    static Semaphore inFlight;
    static final List<String> violations = Collections.synchronizedList(new ArrayList<>());

    // Ledger of everything the service told us, used to prove no double-sell.
    static final Map<String, String> seatOwner = new ConcurrentHashMap<>();     // seat -> reservation id
    static final Map<String, String> keyToReservation = new ConcurrentHashMap<>(); // user|key -> reservation id
    static final Map<String, Integer> liveReservationSeats = new ConcurrentHashMap<>();
    static final LongAdder total201 = new LongAdder();

    record Res(int status, Object body, String reason, long micros, boolean replayed) {
        Map<?, ?> obj() {
            return body instanceof Map<?, ?> m ? m : Map.of();
        }

        String str(String k) {
            Object v = obj().get(k);
            return v == null ? null : v.toString();
        }

        String outcome() {
            if (status == 201) return "201 confirmed";
            if (status == 200) return replayed ? "200 idempotent replay" : "200 ok";
            if (status == 0) return "transport error";
            if (status >= 500) return status + " SERVER ERROR";
            return status + " " + (reason == null ? "" : reason);
        }
    }

    static final class Stats {
        final String name;
        final Map<String, LongAdder> outcomes = new ConcurrentHashMap<>();
        final ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long wallMs;

        Stats(String name) {
            this.name = name;
        }

        void add(Res r) {
            outcomes.computeIfAbsent(r.outcome(), k -> new LongAdder()).increment();
            latencies.add(r.micros());
        }

        long count(String prefix) {
            return outcomes.entrySet().stream().filter(e -> e.getKey().startsWith(prefix))
                    .mapToLong(e -> e.getValue().sum()).sum();
        }

        long fiveXX() {
            return outcomes.entrySet().stream().filter(e -> e.getKey().contains("SERVER ERROR") || e.getKey().startsWith("transport"))
                    .mapToLong(e -> e.getValue().sum()).sum();
        }

        void print() {
            List<Long> l = new ArrayList<>(latencies);
            Collections.sort(l);
            long n = l.size();
            System.out.printf("%n== %s  (%d requests in %d ms, %.0f req/s)%n", name, n, wallMs,
                    wallMs == 0 ? 0.0 : n * 1000.0 / wallMs);
            new TreeMap<>(outcomes).forEach((k, v) -> System.out.printf("   %-34s %7d%n", k, v.sum()));
            if (n > 0) {
                System.out.printf("   latency ms  p50=%.1f  p95=%.1f  p99=%.1f  max=%.1f%n",
                        l.get((int) (n * 0.50)) / 1000.0, l.get((int) Math.min(n - 1, n * 0.95)) / 1000.0,
                        l.get((int) Math.min(n - 1, n * 0.99)) / 1000.0, l.get((int) (n - 1)) / 1000.0);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java scripts/Burst.java <BASE_URL>");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        inFlight = new Semaphore(concurrency);
        System.out.printf("Burst run=%s target=%s concurrency=%d stampede=%d hot_users=%d users=%d hall=%dx%d%n",
                RUN, base, concurrency, stampede, hotUsers, users, rows, cols);

        waitReady();
        Map<String, Double> metricsBefore = scrapeMetrics();

        List<String> seats = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 1; c <= cols; c++) {
                seats.add(rowName(r) + c);
            }
        }
        Res created = call("POST", "/shows", null,
                "{\"name\":\"burst-" + RUN + "\",\"price_paise\":25000,\"per_user_limit\":4,\"seats\":" + jsonArray(seats) + "}",
                Map.of("X-Admin-Key", adminKey));
        if (created.status() != 201) {
            System.err.println("could not create show: " + created.status() + " " + created.body());
            System.exit(1);
        }
        String showId = created.str("id");
        System.out.println("show " + showId + " created with " + seats.size() + " seats");

        int tokenCount = Math.max(users, hotUsers * 6);
        String[] tokens = mintTokens(tokenCount);
        List<Stats> all = new ArrayList<>();

        // 1. Single hot seat storm
        Stats hot = storm("Phase 1: hot-seat storm, " + hotUsers + " users -> A12", hotUsers, i ->
                reserve(tokens[i], "u" + i, showId, List.of("A12"), "hot-" + i));
        expect(hot.count("201") == 1, "A12 must have exactly one 201, got " + hot.count("201"));
        expect(hot.count("409 seat_taken") == hotUsers - 1, "A12 losers must all be 409 seat_taken");
        all.add(hot);

        // 2. Several hot seats at once
        List<String> hotSeats = List.of("A1", "A2", "A3", "A4", "A5");
        Map<String, LongAdder> winsPerSeat = new ConcurrentHashMap<>();
        Stats multiHot = storm("Phase 2: " + hotSeats.size() + " hot seats x " + hotUsers + " users", hotSeats.size() * hotUsers, i -> {
            String seat = hotSeats.get(i % hotSeats.size());
            int u = hotUsers + i;
            Res r = reserve(tokens[u], "u" + u, showId, List.of(seat), "multi-" + i);
            if (r.status() == 201) winsPerSeat.computeIfAbsent(seat, k -> new LongAdder()).increment();
            return r;
        });
        for (String s : hotSeats) {
            long w = winsPerSeat.getOrDefault(s, new LongAdder()).sum();
            expect(w == 1, s + " must have exactly one winner, got " + w);
        }
        all.add(multiHot);

        // 3. General stampede, skewed to the front rows, with concurrent same-key retries
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong snapshots = new AtomicLong();
        Thread poller = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                Res s = call("GET", "/shows/" + showId, null, null, Map.of(), false);
                if (s.status() == 200) {
                    snapshots.incrementAndGet();
                    Map<?, ?> c = (Map<?, ?>) s.obj().get("counts");
                    long sum = num(c.get("available")) + num(c.get("held")) + num(c.get("confirmed"));
                    expect(sum == num(c.get("total")), "invariant broken mid-burst: " + c);
                }
                sleep(200);
            }
        });
        int stampedeRows = Math.max(1, rows - 1); // last row is reserved for the functional phases
        Stats general = storm("Phase 3: stampede " + stampede + " reserves (" + retryPct + "% duplicated retries)", stampede, i -> {
            // Requests 2k and 2k+1 form a pair; for RETRY_PCT of pairs both carry the identical
            // (user, seats, key) and race each other, simulating a client retry mid-flight.
            int pair = i / 2;
            boolean retried = pair % 100 < retryPct;
            int seed = retried ? pair * 2 : i;
            Random pick = new Random(seed * 104729L + 17);
            int u = pick.nextInt(users);
            int row = pick.nextDouble() < 0.6 ? pick.nextInt(Math.min(3, stampedeRows)) : pick.nextInt(stampedeRows);
            int col = 1 + pick.nextInt(cols - 1);
            List<String> want = pick.nextDouble() < 0.2
                    ? List.of(rowName(row) + col, rowName(row) + (col + 1))
                    : List.of(rowName(row) + col);
            return reserve(tokens[u], "u" + u, showId, want, "st-" + seed);
        });
        stop.set(true);
        poller.join();
        System.out.println("   invariant checked on " + snapshots.get() + " live snapshots during the stampede");
        all.add(general);

        // 4. Idempotency race: one key, 20 parallel identical requests, then same key + different seats
        String last = rowName(rows - 1);
        String idemTok = token("idem-" + RUN);
        Stats idem = storm("Phase 4: same idempotency key x20 in parallel", 20, i ->
                reserve(idemTok, "idem-" + RUN, showId, List.of(last + "1"), "the-one-key"));
        expect(idem.count("201") == 1, "same key must produce exactly one 201, got " + idem.count("201"));
        expect(idem.count("200") == 19, "the other 19 must be 200 replays, got " + idem.count("200"));
        Res mismatch = reserve(idemTok, "idem-" + RUN, showId, List.of(last + "2"), "the-one-key");
        idem.add(mismatch);
        expect(mismatch.status() == 409 && "idempotency_key_reused".equals(mismatch.reason()),
                "same key with different seats must be 409 idempotency_key_reused, got " + mismatch.outcome());
        all.add(idem);

        // 5. Per-user limit race: one user, 10 parallel single-seat reserves, limit 4
        String greedyId = "greedy-" + RUN;
        String greedy = token(greedyId);
        Stats limit = storm("Phase 5: one user, 10 parallel reserves, limit 4", 10, i ->
                reserve(greedy, greedyId, showId, List.of(last + (10 + i)), "greedy-" + i));
        expect(limit.count("201") <= 4, "per-user limit exceeded: " + limit.count("201") + " confirmed");
        Res mine = call("GET", "/me/reservations?show_id=" + showId, greedy, null, Map.of());
        long held = 0;
        if (mine.body() instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                if ("confirmed".equals(m.get("status"))) held += ((List<?>) m.get("seats")).size();
            }
        }
        expect(held <= 4, "user holds " + held + " seats, limit is 4");
        System.out.println("   greedy user ends with " + held + " seats (limit 4)");
        all.add(limit);

        // 6. Identity: body user_id is ignored; only the owner can cancel; cancel never resurrects
        Stats ident = new Stats("Phase 6: spoofing, owner-only cancel, rebook");
        String malloryId = "mallory-" + RUN, victimId = "victim-" + RUN, otherId = "other-" + RUN;
        String mallory = token(malloryId), victim = token(victimId), other = token(otherId);
        Res spoof = call("POST", "/shows/" + showId + "/reserve", mallory,
                "{\"seats\":[\"" + last + "30\"],\"idempotency_key\":\"spoof\",\"user_id\":\"" + victimId + "\"}", Map.of());
        record(ident, spoof, malloryId, "spoof", List.of(last + "30"));
        expect(malloryId.equals(spoof.str("user_id")), "spoofed body user_id was honoured: " + spoof.str("user_id"));
        Res vr = reserve(victim, victimId, showId, List.of(last + "31"), "v1");
        ident.add(vr);
        String vrid = vr.str("reservation_id");
        Res steal = call("POST", "/reservations/" + vrid + "/cancel", mallory, null, Map.of());
        ident.add(steal);
        expect(steal.status() == 404, "non-owner cancel must be 404, got " + steal.status());
        Res own = call("POST", "/reservations/" + vrid + "/cancel", victim, null, Map.of());
        ident.add(own);
        expect(own.status() == 200 && "cancelled".equals(own.str("status")), "owner cancel failed: " + own.outcome());
        if (own.status() == 200) {
            liveReservationSeats.remove(vrid);
            seatOwner.remove(last + "31", vrid);
        }
        Res rebook = reserve(other, otherId, showId, List.of(last + "31"), "o1");
        ident.add(rebook);
        expect(rebook.status() == 201, "released seat must be re-bookable, got " + rebook.outcome());
        Res again = call("POST", "/reservations/" + vrid + "/cancel", victim, null, Map.of());
        ident.add(again);
        String seatState = seatStatus(showId, last + "31");
        expect("confirmed".equals(seatState), "repeat cancel resurrected a seat now owned by someone else: " + seatState);
        all.add(ident);

        // Final reconciliation
        sleep(2500); // let the 1s seat gauges refresh
        Res fin = call("GET", "/shows/" + showId, null, null, Map.of());
        Map<?, ?> c = (Map<?, ?>) fin.obj().get("counts");
        long avail = num(c.get("available")), heldC = num(c.get("held")), conf = num(c.get("confirmed")), tot = num(c.get("total"));
        long ledgerSeats = liveReservationSeats.values().stream().mapToLong(Integer::longValue).sum();
        Map<String, Double> metricsAfter = scrapeMetrics();

        System.out.println();
        System.out.println("================ OUTCOME DISTRIBUTION ================");
        all.forEach(Stats::print);
        Map<String, Long> totals = new TreeMap<>();
        all.forEach(s -> s.outcomes.forEach((k, v) -> totals.merge(k, v.sum(), Long::sum)));
        System.out.println("\n== TOTAL");
        totals.forEach((k, v) -> System.out.printf("   %-34s %7d%n", k, v));
        long fiveXX = all.stream().mapToLong(Stats::fiveXX).sum();
        System.out.printf("   %-34s %7d%n", "5xx + transport errors", fiveXX);
        expect(fiveXX == 0, fiveXX + " server errors / transport failures during the burst");

        System.out.println("\n================ RECONCILIATION ================");
        System.out.printf("   GET /shows: available=%d held=%d confirmed=%d total=%d  (sum=%d)%n",
                avail, heldC, conf, tot, avail + heldC + conf);
        expect(avail + heldC + conf == tot, "available + held + confirmed != total");
        System.out.printf("   seats in live reservations returned to us: %d  vs confirmed in API: %d%n", ledgerSeats, conf);
        expect(ledgerSeats == conf, "API confirmed count disagrees with the reservations the API handed out");

        if (metricsBefore != null && metricsAfter != null) {
            double dConfirmed = delta(metricsBefore, metricsAfter, "reservations_confirmed_total");
            double dTaken = delta(metricsBefore, metricsAfter, "reservations_declined_total{reason=\"seat_taken\"}");
            double dLimit = delta(metricsBefore, metricsAfter, "reservations_declined_total{reason=\"per_user_limit\"}");
            double dReplay = delta(metricsBefore, metricsAfter, "reservations_declined_total{reason=\"idempotent_replay\"}");
            Double gauge = metricsAfter.get("seats_available{show_id=\"" + showId + "\"}");
            System.out.printf("   metrics delta: confirmed=%.0f (client saw %d 201s)  seat_taken=%.0f (client %d)  per_user_limit=%.0f (client %d)  idempotent_replay=%.0f (client %d)%n",
                    dConfirmed, total201.sum(), dTaken, totals.getOrDefault("409 seat_taken", 0L),
                    dLimit, totals.getOrDefault("409 per_user_limit", 0L), dReplay, totals.getOrDefault("200 idempotent replay", 0L));
            System.out.printf("   gauge seats_available=%s  vs API available=%d%n", gauge, avail);
            expect(gauge != null && gauge.longValue() == avail, "seats_available gauge does not match API");
            if (dConfirmed != total201.sum()) {
                System.out.println("   note: counter delta differs from this client's 201s - other traffic or more than one instance?");
            }
        } else {
            System.out.println("   /metrics not reachable; skipped metric reconciliation");
        }

        System.out.println();
        if (violations.isEmpty()) {
            System.out.println("PASS: no double-sell, no 5xx, invariant held, idempotency and per-user limit held.");
        } else {
            System.out.println("FAIL: " + violations.size() + " violation(s):");
            violations.stream().distinct().limit(50).forEach(v -> System.out.println("   - " + v));
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- phases helpers

    static Stats storm(String name, int n, java.util.function.IntFunction<Res> task) throws Exception {
        Stats stats = new Stats(name);
        System.out.println("running " + name + " ...");
        CountDownLatch gate = new CountDownLatch(1);
        long start;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    gate.await();
                    stats.add(task.apply(idx));
                    return null;
                }));
            }
            start = System.nanoTime();
            gate.countDown();
            for (Future<?> f : fs) {
                f.get();
            }
        }
        stats.wallMs = (System.nanoTime() - start) / 1_000_000;
        return stats;
    }

    static Res reserve(String token, String userId, String showId, List<String> seats, String key) {
        Res r = call("POST", "/shows/" + showId + "/reserve", token,
                "{\"seats\":" + jsonArray(seats) + ",\"idempotency_key\":\"" + key + "\"}", Map.of());
        if (r.status() == 201 || r.status() == 200) {
            String rid = r.str("reservation_id");
            String prev = keyToReservation.putIfAbsent(userId + "|" + key, rid);
            expect(prev == null || prev.equals(rid), "key " + key + " produced two reservations: " + prev + " and " + rid);
            expect(userId.equals(r.str("user_id")), "reservation returned for wrong user");
        }
        if (r.status() == 201) {
            total201.increment();
            List<?> got = (List<?>) r.obj().get("seats");
            liveReservationSeats.put(r.str("reservation_id"), got.size());
            for (Object s : got) {
                String prev = seatOwner.putIfAbsent(s.toString(), r.str("reservation_id"));
                expect(prev == null, "DOUBLE SELL: seat " + s + " confirmed to " + prev + " and " + r.str("reservation_id"));
            }
        }
        return r;
    }

    static void record(Stats st, Res r, String userId, String key, List<String> seats) {
        st.add(r);
        if (r.status() == 201) {
            total201.increment();
            liveReservationSeats.put(r.str("reservation_id"), seats.size());
            seats.forEach(s -> {
                String prev = seatOwner.putIfAbsent(s, r.str("reservation_id"));
                expect(prev == null, "DOUBLE SELL: seat " + s);
            });
        }
    }

    static String seatStatus(String showId, String label) {
        Res s = call("GET", "/shows/" + showId, null, null, Map.of());
        for (Object o : (List<?>) s.obj().get("seats")) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (label.equals(m.get("label"))) return (String) m.get("status");
        }
        return null;
    }

    static String[] mintTokens(int n) throws Exception {
        System.out.println("minting " + n + " user tokens ...");
        String[] out = new String[n];
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    out[idx] = token("u" + idx);
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get();
        }
        return out;
    }

    static String token(String userId) {
        for (int attempt = 0; attempt < 5; attempt++) {
            Res r = call("POST", "/auth/token", null, "{\"user_id\":\"" + userId + "\"}", Map.of());
            if (r.status() == 200) return r.str("token");
            sleep(500);
        }
        throw new IllegalStateException("could not mint token for " + userId);
    }

    static void waitReady() {
        long deadline = System.currentTimeMillis() + 240_000;
        System.out.print("waiting for " + base + "/readyz (cold start allowed) ");
        while (System.currentTimeMillis() < deadline) {
            Res r = call("GET", "/readyz", null, null, Map.of());
            if (r.status() == 200) {
                System.out.println("ready");
                return;
            }
            System.out.print(".");
            sleep(2000);
        }
        System.err.println("\nservice never became ready");
        System.exit(1);
    }

    static Map<String, Double> scrapeMetrics() {
        Res r = call("GET", "/metrics", null, null, Map.of());
        if (r.status() != 200 || !(r.body() instanceof String text)) return null;
        Map<String, Double> m = new HashMap<>();
        for (String line : text.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue;
            int sp = line.lastIndexOf(' ');
            try {
                m.put(line.substring(0, sp), Double.parseDouble(line.substring(sp + 1)));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    static double delta(Map<String, Double> before, Map<String, Double> after, String key) {
        return after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0);
    }

    // ---------------------------------------------------------------- http + json

    static Res call(String method, String path, String token, String body, Map<String, String> headers) {
        return call(method, path, token, body, headers, true);
    }

    static Res call(String method, String path, String token, String body, Map<String, String> headers, boolean limited) {
        long start = System.nanoTime();
        try {
            if (limited) inFlight.acquire();
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(90))
                        .header("X-Request-Id", "burst-" + RUN + "-" + UUID.randomUUID().toString().substring(0, 8));
                if (body != null) {
                    b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                if (token != null) b.header("Authorization", "Bearer " + token);
                headers.forEach(b::header);
                HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                long micros = (System.nanoTime() - start) / 1000;
                String ct = resp.headers().firstValue("Content-Type").orElse("");
                Object parsed = ct.contains("json") && !resp.body().isEmpty() ? new Json(resp.body()).parse() : resp.body();
                String reason = parsed instanceof Map<?, ?> m && m.get("reason") != null ? m.get("reason").toString() : null;
                boolean replayed = "true".equals(resp.headers().firstValue("Idempotent-Replayed").orElse(""));
                return new Res(resp.statusCode(), parsed, reason, micros, replayed);
            } finally {
                if (limited) inFlight.release();
            }
        } catch (Exception e) {
            return new Res(0, e.toString(), "transport:" + e.getClass().getSimpleName(), (System.nanoTime() - start) / 1000, false);
        }
    }

    static String jsonArray(List<String> xs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(xs.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    /** Tiny JSON parser (objects, arrays, strings, numbers, booleans, null). */
    static final class Json {
        private final String s;
        private int i;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            ws();
            Object v = value();
            ws();
            return v;
        }

        private Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{': {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (s.charAt(i) == '}') { i++; return m; }
                    while (true) {
                        ws();
                        String k = string();
                        ws();
                        i++; // ':'
                        m.put(k, value());
                        ws();
                        if (s.charAt(i++) == '}') return m;
                    }
                }
                case '[': {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (s.charAt(i) == ']') { i++; return l; }
                    while (true) {
                        l.add(value());
                        ws();
                        if (s.charAt(i++) == ']') return l;
                    }
                }
                case '"':
                    return string();
                case 't': i += 4; return Boolean.TRUE;
                case 'f': i += 5; return Boolean.FALSE;
                case 'n': i += 4; return null;
                default: {
                    int st = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                    return Double.parseDouble(s.substring(st, i));
                }
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++; // opening quote
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    // ---------------------------------------------------------------- misc

    static String rowName(int r) {
        return r < 26 ? String.valueOf((char) ('A' + r)) : "R" + r;
    }

    static long num(Object o) {
        return o == null ? -1 : ((Number) o).longValue();
    }

    static void expect(boolean ok, String message) {
        if (!ok) violations.add(message);
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }
}
