import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** No dependencies: regression checks for failures the load generator must not hide. */
public class BurstChecksTest {
    static int checks;

    public static void main(String[] args) throws Exception {
        Burst.warmingUp = false;
        Burst.Stats observed = new Burst.Stats("test");
        observed.add(response(201, null, false));
        Map<String, Double> before = counters();
        Map<String, Double> after = counters();
        after.put("reservations_confirmed_total", 1.0);
        after.put("seats_available{show_id=\"test\"}", 9.0);
        check(false, () -> Burst.checkMetrics(before, after, "test", 9, observed));
        check(true, () -> Burst.checkMetrics(null, after, "test", 9, observed));
        check(true, () -> Burst.checkMetrics(before, null, "test", 9, observed));
        for (String key : before.keySet()) {
            Map<String, Double> wrong = new HashMap<>(after);
            wrong.put(key, wrong.get(key) + 1);
            check(true, () -> Burst.checkMetrics(before, wrong, "test", 9, observed));
            wrong.remove(key);
            check(true, () -> Burst.checkMetrics(before, wrong, "test", 9, observed));
        }
        check(true, () -> Burst.checkMetrics(before, after, "test", 8, observed));
        check(true, () -> Burst.checkState(response(503, null, false), 10));
        check(true, () -> Burst.checkState(response(200, null, false), 10));
        var validState = new Burst.Res(200, Map.of("counts", Map.of("available", 9, "held", 0,
                "confirmed", 1, "total", 10), "reconciled", true), null, 1, false);
        check(false, () -> Burst.checkState(validState, 10));
        check(true, () -> Burst.checkState(validState, 11));
        check(true, () -> Burst.observe("GET", "/shows/test", response(503, null, false)));
        check(true, () -> Burst.observe("POST", "/shows/test/reserve", response(200, null, false)));
        check(true, () -> Burst.observe("POST", "/shows/test/reserve", response(409, "unknown", false)));
        check(true, () -> Burst.observe("POST", "/shows/test/reserve", response(409, null, false)));
        check(Burst.strict, () -> Burst.observe("POST", "/shows/test/reserve", response(429, "overloaded", false)));
        long shed = Burst.reserveAttempts.count("429");
        check(false, () -> Burst.observe("POST", "/shows/test/reserve", response(201, null, false)));
        if (Burst.reserveAttempts.count("429") != shed || shed != 1) throw new AssertionError("retry hid a 429");
        if (Burst.strict && (Burst.concurrency != 20000 || Burst.stampede != 20000 || Burst.retries429 != 0)) {
            throw new AssertionError("strict settings were weakened by environment overrides");
        }
        if (!Long.valueOf(9007199254740993L).equals(new Burst.Json("9007199254740993").parse())) {
            throw new AssertionError("JSON integer lost precision");
        }
        var diagnosticHeaders = Map.of("Server", List.of("cloudflare"), "CF-Mitigated", List.of("challenge"),
                "Set-Cookie", List.of("private-cookie"), "Authorization", List.of("private-token"));
        String attribution = Burst.failureSource(429, diagnosticHeaders, "<title>Just a moment</title>");
        if (!attribution.contains("ingress=absent") || !attribution.contains("mitigation=challenge")
                || !attribution.contains("challenge_page=true")) throw new AssertionError(attribution);
        if (Burst.failureMetadata(diagnosticHeaders).toString().contains("private-")) {
            throw new AssertionError("diagnostics leaked sensitive headers");
        }
        checks += 2;
        stalledBodyDeadline();
        System.out.println("PASS: " + checks + " burst verifier checks; strict=" + Burst.strict);
    }

    static void stalledBodyDeadline() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var client = java.net.http.HttpClient.newHttpClient()) {
            Thread peer = Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) {
                    var reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) { }
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\n\r\nx".getBytes());
                    socket.getOutputStream().flush();
                    release.await(2, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) { throw new RuntimeException(e); }
            });
            long start = System.nanoTime();
            try {
                Burst.sendComplete(client, java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create("http://localhost:" + server.getLocalPort()))
                        .timeout(java.time.Duration.ofMillis(200)).build());
                throw new AssertionError("partial body was treated as complete");
            } catch (java.net.http.HttpTimeoutException expected) {
                if ((System.nanoTime() - start) / 1_000_000 > 1500) throw new AssertionError("body deadline not enforced");
            } finally {
                release.countDown();
                peer.join();
            }
        }
        checks++;
    }

    static Burst.Res response(int status, String reason, boolean replayed) {
        return new Burst.Res(status, Map.of(), reason, 1, replayed);
    }

    static Map<String, Double> counters() {
        Map<String, Double> metrics = new HashMap<>();
        metrics.put("reservations_confirmed_total", 0.0);
        for (String reason : List.of("seat_taken", "per_user_limit", "idempotent_replay", "idempotency_key_reused")) {
            metrics.put("reservations_declined_total{reason=\"" + reason + "\"}", 0.0);
        }
        return metrics;
    }

    static void check(boolean shouldFail, Runnable action) {
        Burst.violations.clear();
        action.run();
        if (Burst.violations.isEmpty() == shouldFail) {
            throw new AssertionError("Expected failure=" + shouldFail + ": " + Burst.violations);
        }
        checks++;
    }
}
