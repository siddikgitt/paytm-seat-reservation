package com.paytm.seats;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal blocking HTTP client for exercising the service end to end in tests. */
final class ApiClient {

    record Response(int status, JsonNode body, Map<String, List<String>> headers) {
        String reason() {
            return body.path("reason").asText(null);
        }

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String base;
    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    ApiClient(String base) {
        this.base = base;
    }

    String token(String userId) {
        return post("/auth/token", null, Map.of("user_id", userId), Map.of()).body().get("token").asText();
    }

    JsonNode createShow(List<String> seats, long pricePaise, int perUserLimit) {
        Response r = post("/shows", null,
                Map.of("name", "test-show", "seats", seats, "price_paise", pricePaise, "per_user_limit", perUserLimit),
                Map.of("X-Admin-Key", "dev-admin-key"));
        if (r.status() != 201) {
            throw new IllegalStateException("create show failed: " + r);
        }
        return r.body();
    }

    Response reserve(String token, String showId, List<String> seats, String key) {
        return post("/shows/" + showId + "/reserve", token, Map.of("seats", seats, "idempotency_key", key), Map.of());
    }

    Response reserveRaw(String token, String showId, Object body) {
        return post("/shows/" + showId + "/reserve", token, body, Map.of());
    }

    Response cancel(String token, String reservationId) {
        return post("/reservations/" + reservationId + "/cancel", token, null, Map.of());
    }

    Response show(String showId) {
        return get("/shows/" + showId, null);
    }

    Response get(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return send(b.build());
    }

    Response post(String path, String token, Object body, Map<String, String> headers) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json")
                    .POST(body == null ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if (token != null) {
                b.header("Authorization", "Bearer " + token);
            }
            headers.forEach(b::header);
            return send(b.build());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private Response send(HttpRequest request) {
        try {
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            String contentType = r.headers().firstValue("Content-Type").orElse("");
            JsonNode node = r.body() == null || r.body().isBlank() || !contentType.contains("json")
                    ? JSON.createObjectNode() : JSON.readTree(r.body());
            return new Response(r.statusCode(), node, r.headers().map());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
