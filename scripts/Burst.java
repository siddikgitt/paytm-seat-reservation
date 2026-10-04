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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * On-sale stampede against a live deployment. Zero dependencies: run with `java scripts/Burst.java <BASE_URL>`.
 *
 * Phases: hot-seat storm (many users, one seat), multi hot-seat storm, general stampede with same-key
 * retries, idempotency race, per-user-limit race, identity spoofing and cancel/rebook. Prints the outcome
 * distribution and a final reconciliation against GET /shows/{id} and /metrics. Exits 1 on any violation.
 *
 * Env: ADMIN_KEY (dev-admin-key), CONCURRENCY (1000), STAMPEDE (20000), HOT_USERS (500), USERS (2000),
 *      ROWS (20), COLS (100), RETRY_PCT (10), RETRIES_429 (5; 0 = report 429s without retrying).
 */
public class Burst {

    static String base;
    static String adminKey = env("ADMIN_KEY", "dev-admin-key");
    static final boolean strict = Boolean.parseBoolean(env("STRICT", "false"));
    static int concurrency = strict ? 20000 : Integer.parseInt(env("CONCURRENCY", "1000"));
    static int stampede = strict ? 20000 : Integer.parseInt(env("STAMPEDE", "20000"));
    static int hotUsers = Integer.parseInt(env("HOT_USERS", "500"));
    static int users = Integer.parseInt(env("USERS", "2000"));
    static int rows = Integer.parseInt(env("ROWS", "20"));
    static int cols = Integer.parseInt(env("COLS", "100"));
    static int retryPct = Integer.parseInt(env("RETRY_PCT", "10"));
    static int retries429 = strict ? 0 : Integer.parseInt(env("RETRIES_429", "5"));
    static final LongAdder shed429 = new LongAdder();
    static final Stats warmupAttempts = new Stats("Cold-start readiness attempts (before burst)");
    static final Stats attempts = new Stats("Every HTTP attempt after readiness (including setup, probes and retries)");
    static final Stats reserveAttempts = new Stats("Every reservation HTTP attempt");
    static final AtomicInteger activeHttp = new AtomicInteger();
    static final AtomicInteger peakHttp = new AtomicInteger();
    static final Map<String, LongAdder> protocols = new ConcurrentHashMap<>();
    static volatile boolean warmingUp = true;
    static final String RUN = Long.toString(System.currentTimeMillis() % 1_000_000_000L, 36);

    // A single JDK HTTP/2 connection fails immediately when the peer's stream limit is reached.
    // Keep at most 64 requests per client, with enough total slots for the entire burst + probes.
    static class Clients {
        static final BlockingQueue<HttpClient> slots = create();
        static BlockingQueue<HttpClient> create() {
            int count = (concurrency + 63) / 64 + 1;
            var executor = Executors.newVirtualThreadPerTaskExecutor();
            var clients = new ArrayList<HttpClient>();
            for (int i = 0; i < count; i++) clients.add(HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(strict ? 120 : 20))
                    .executor(executor).build());
            var queue = new ArrayBlockingQueue<HttpClient>(count * 64);
            for (int slot = 0; slot < 64; slot++) queue.addAll(clients);
            return queue;
        }
    }
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
            if (status == 0) return "transport error " + reason;
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

    public static void main(String[] args) {
        try {
            run(args);
        } catch (Exception e) {
            attempts.print();
            reserveAttempts.print();
            System.err.println("FAIL: burst aborted: " + e);
            violations.stream().distinct().limit(50).forEach(v -> System.err.println("   - " + v));
            System.exit(1);
        }
    }

    static void run(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: java scripts/Burst.java <BASE_URL>");
            System.exit(2);
        }
        if (concurrency < 1 || stampede < 1 || hotUsers < 2 || users < 1 || rows < 2 || cols < 32
                || retryPct < 0 || retryPct > 100 || retries429 < 0) {
            throw new IllegalArgumentException("invalid burst settings: rows >= 2, cols >= 32, positive concurrency/users required");
        }
        base = args[0].replaceAll("/+$", "");
        inFlight = new Semaphore(concurrency);
        System.out.printf("Burst run=%s target=%s concurrency=%d stampede=%d hot_users=%d users=%d hall=%dx%d%n",
                RUN, base, concurrency, stampede, hotUsers, users, rows, cols);

        System.out.println("strict=" + strict + " retries_429=" + retries429);
        waitReady();
        warmingUp = false;
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
        expect(multiHot.count("409 seat_taken") == hotSeats.size() * (hotUsers - 1),
                "every multi-hot loser must be 409 seat_taken");
        all.add(multiHot);

        // 3. General stampede, skewed to the front rows, with concurrent same-key retries
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong snapshots = new AtomicLong();
        Thread poller = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                Res s = call("GET", "/shows/" + showId, null, null, Map.of(), false);
                if (checkState(s, seats.size())) snapshots.incrementAndGet();
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
        expect(snapshots.get() > 0, "no valid state snapshots collected during stampede");
        expect(general.count("201") + general.count("200 idempotent replay")
                        + general.count("409 seat_taken") + general.count("409 per_user_limit") == stampede,
                "stampede contained unexpected final outcomes");
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
        expect(limit.count("201") == 4 && limit.count("409 per_user_limit") == 6, "per-user limit exceeded: " + limit.count("201") + " confirmed");
        Res mine = call("GET", "/me/reservations?show_id=" + showId, greedy, null, Map.of());
        expect(mine.status() == 200 && mine.body() instanceof List<?>, "cannot read user reservations");
        long held = 0;
        if (mine.body() instanceof List<?> list) {
            for (Object o : list) {
                Map<?, ?> m = (Map<?, ?>) o;
                if ("confirmed".equals(m.get("status"))) held += ((List<?>) m.get("seats")).size();
            }
        }
        expect(held == 4, "user holds " + held + " seats, limit is 4");
        System.out.println("   greedy user ends with " + held + " seats (limit 4)");
        all.add(limit);

        // 6. Identity: body user_id is ignored; only the owner can cancel; cancel never resurrects
        Stats ident = new Stats("Phase 6: spoofing, owner-only cancel, rebook");
        String malloryId = "mallory-" + RUN, victimId = "victim-" + RUN, otherId = "other-" + RUN;
        String mallory = token(malloryId), victim = token(victimId), other = token(otherId);
        Res spoof = call("POST", "/shows/" + showId + "/reserve", mallory,
                "{\"seats\":[\"" + last + "30\"],\"idempotency_key\":\"spoof\",\"user_id\":\"" + victimId + "\"}", Map.of());
        record(ident, spoof, malloryId, "spoof", List.of(last + "30"));
        expect(spoof.status() == 201 && malloryId.equals(spoof.str("user_id")), "spoofed body user_id was honoured: " + spoof.str("user_id"));
        Res vr = reserve(victim, victimId, showId, List.of(last + "31"), "v1");
        ident.add(vr);
        expect(vr.status() == 201, "victim reserve must succeed");
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
        expect(again.status() == 200 && "cancelled".equals(again.str("status")), "repeat cancel must be idempotent");
        String seatState = seatStatus(showId, last + "31");
        expect("confirmed".equals(seatState), "repeat cancel resurrected a seat now owned by someone else: " + seatState);
        all.add(ident);

        // Final reconciliation
        sleep(2500); // let the 1s seat gauges refresh
        Res fin = call("GET", "/shows/" + showId, null, null, Map.of());
        if (!checkState(fin, seats.size())) throw new IllegalStateException("final show state is invalid: " + fin);
        Map<?, ?> c = (Map<?, ?>) fin.obj().get("counts");
        long avail = num(c.get("available")), heldC = num(c.get("held")), conf = num(c.get("confirmed")), tot = num(c.get("total"));
        long ledgerSeats = liveReservationSeats.values().stream().mapToLong(Integer::longValue).sum();
        Map<String, Double> metricsAfter = scrapeMetrics();

        System.out.println();
        System.out.println("================ OUTCOME DISTRIBUTION ================");
        all.forEach(Stats::print);
        warmupAttempts.print();
        attempts.print();
        reserveAttempts.print();
        System.out.println("Peak outstanding HTTP attempts: " + peakHttp.get());
        System.out.println("Negotiated response protocols: " + protocols);
        Map<String, Long> totals = new TreeMap<>();
        all.forEach(s -> s.outcomes.forEach((k, v) -> totals.merge(k, v.sum(), Long::sum)));
        System.out.println("\n== TOTAL");
        totals.forEach((k, v) -> System.out.printf("   %-34s %7d%n", k, v));
        long fiveXX = attempts.fiveXX();
        System.out.printf("   %-34s %7d%n", "5xx + transport errors", fiveXX);
        System.out.printf("   %-34s %7d%n", "429 shed then retried (same key)", shed429.sum());
        expect(fiveXX == 0, fiveXX + " server errors / transport failures during the burst");

        System.out.println("\n================ RECONCILIATION ================");
        System.out.printf("   GET /shows: available=%d held=%d confirmed=%d total=%d  (sum=%d)%n",
                avail, heldC, conf, tot, avail + heldC + conf);
        expect(avail + heldC + conf == tot, "available + held + confirmed != total");
        System.out.printf("   seats in live reservations returned to us: %d  vs confirmed in API: %d%n", ledgerSeats, conf);
        expect(ledgerSeats == conf, "API confirmed count disagrees with the reservations the API handed out");

        checkMetrics(metricsBefore, metricsAfter, showId, avail, reserveAttempts);

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
        CountDownLatch ready = new CountDownLatch(n);
        long start;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    ready.countDown();
                    gate.await();
                    stats.add(task.apply(idx));
                    return null;
                }));
            }
            ready.await();
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
        String body = "{\"seats\":" + jsonArray(seats) + ",\"idempotency_key\":\"" + key + "\"}";
        Res r = call("POST", "/shows/" + showId + "/reserve", token, body, Map.of());
        // 429 = load shed before any state change; a real client retries with the same key.
        for (int attempt = 0; r.status() == 429 && attempt < retries429; attempt++) {
            shed429.increment();
            sleep(1000L + new Random().nextInt(2000));
            r = call("POST", "/shows/" + showId + "/reserve", token, body, Map.of());
        }
        expect(r.status() != 429, "reservation retries exhausted with 429");
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
        // Setup is outside the on-sale race. Keep token minting modest on free hosts.
        Semaphore tokenSlots = new Semaphore(8);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                int idx = i;
                fs.add(pool.submit(() -> {
                    tokenSlots.acquire();
                    try { out[idx] = token("u" + idx); }
                    finally { tokenSlots.release(); }
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

    static boolean checkState(Res response, long total) {
        if (response.status() != 200 || !(response.obj().get("counts") instanceof Map<?, ?> c)) {
            expect(false, "state poll failed: " + response.outcome());
            return false;
        }
        try {
            long available = num(c.get("available")), held = num(c.get("held")), confirmed = num(c.get("confirmed"));
            boolean ok = available >= 0 && held >= 0 && confirmed >= 0 && num(c.get("total")) == total
                    && available + held + confirmed == total && Boolean.TRUE.equals(response.obj().get("reconciled"));
            expect(ok, "state reconciliation failed: " + c);
            return ok;
        } catch (RuntimeException e) {
            expect(false, "malformed state counts: " + c);
            return false;
        }
    }

    static void checkMetrics(Map<String, Double> before, Map<String, Double> after,
                             String showId, long available, Stats observed) {
        if (before == null || after == null) {
            expect(false, "/metrics unavailable; reconciliation is required");
            return;
        }
        Map<String, Long> expected = new LinkedHashMap<>();
        expected.put("reservations_confirmed_total", observed.count("201"));
        expected.put("reservations_declined_total{reason=\"seat_taken\"}", observed.count("409 seat_taken"));
        expected.put("reservations_declined_total{reason=\"per_user_limit\"}", observed.count("409 per_user_limit"));
        expected.put("reservations_declined_total{reason=\"idempotent_replay\"}", observed.count("200 idempotent replay"));
        expected.put("reservations_declined_total{reason=\"idempotency_key_reused\"}", observed.count("409 idempotency_key_reused"));
        expected.forEach((key, count) -> {
            double actual = delta(before, after, key);
            System.out.printf("   %s delta=%.0f client=%d%n", key, actual, count);
            expect(before.containsKey(key) && after.containsKey(key) && actual == count,
                    "counter missing or inconsistent: " + key + " delta=" + actual + " client=" + count);
        });
        Double gauge = after.get("seats_available{show_id=\"" + showId + "\"}");
        System.out.println("   seats_available=" + gauge + " API=" + available);
        expect(gauge != null && gauge == (double) available, "seats_available gauge does not match API");
    }

    static double delta(Map<String, Double> before, Map<String, Double> after, String key) {
        return after.getOrDefault(key, 0.0) - before.getOrDefault(key, 0.0);
    }

    // ---------------------------------------------------------------- http + json

    static Res call(String method, String path, String token, String body, Map<String, String> headers) {
        return call(method, path, token, body, headers, true);
    }

    static Res call(String method, String path, String token, String body, Map<String, String> headers, boolean limited) {
        Res result = send(method, path, token, body, headers, limited);
        observe(method, path, result);
        return result;
    }

    static void observe(String method, String path, Res r) {
        if (warmingUp && path.equals("/readyz")) {
            warmupAttempts.add(r);
            return;
        }
        attempts.add(r);
        if (path.endsWith("/reserve")) reserveAttempts.add(r);
        expect(r.status() != 0 && r.status() < 500, method + " " + path + ": " + r.outcome());
        boolean expected;
        if (path.endsWith("/reserve")) {
            expected = r.status() == 201 || (r.status() == 200 && r.replayed())
                    || (r.status() == 409 && List.of("seat_taken", "per_user_limit", "idempotency_key_reused").contains(r.reason() == null ? "" : r.reason()))
                    || (!strict && r.status() == 429 && "overloaded".equals(r.reason()));
        } else if (path.endsWith("/cancel")) {
            expected = r.status() == 200 || r.status() == 404;
        } else {
            expected = r.status() == (method.equals("POST") && path.equals("/shows") ? 201 : 200);
        }
        expect(expected, "unexpected response: " + method + " " + path + ": " + r.outcome());
    }

    static Res send(String method, String path, String token, String body, Map<String, String> headers, boolean limited) {
        long start = System.nanoTime();
        try {
            if (limited) inFlight.acquire();
            peakHttp.accumulateAndGet(activeHttp.incrementAndGet(), Math::max);
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                        .timeout(Duration.ofSeconds(strict ? 600 : 90))
                        .header("X-Request-Id", "burst-" + RUN + "-" + UUID.randomUUID().toString().substring(0, 8));
                if (body != null) {
                    b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
                } else {
                    b.method(method, HttpRequest.BodyPublishers.noBody());
                }
                if (token != null) b.header("Authorization", "Bearer " + token);
                headers.forEach(b::header);
                HttpClient client = Clients.slots.take();
                HttpResponse<String> resp;
                try {
                    resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
                } finally {
                    Clients.slots.add(client);
                }
                protocols.computeIfAbsent(resp.version().name(), k -> new LongAdder()).increment();
                long micros = (System.nanoTime() - start) / 1000;
                String ct = resp.headers().firstValue("Content-Type").orElse("");
                Object parsed = ct.contains("json") && !resp.body().isEmpty() ? new Json(resp.body()).parse() : resp.body();
                String reason = parsed instanceof Map<?, ?> m && m.get("reason") != null ? m.get("reason").toString() : null;
                boolean replayed = "true".equals(resp.headers().firstValue("Idempotent-Replayed").orElse(""));
                return new Res(resp.statusCode(), parsed, reason, micros, replayed);
            } finally {
                activeHttp.decrementAndGet();
                if (limited) inFlight.release();
            }
        } catch (Exception e) {
            String detail = e instanceof java.io.IOException && "too many concurrent streams".equals(e.getMessage())
                    ? ":too_many_concurrent_streams" : "";
            return new Res(0, e.toString(), "transport:" + e.getClass().getSimpleName() + detail, (System.nanoTime() - start) / 1000, false);
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
                    String number = s.substring(st, i);
                    if (number.contains(".") || number.contains("e") || number.contains("E")) return Double.parseDouble(number);
                    return Long.parseLong(number);
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
