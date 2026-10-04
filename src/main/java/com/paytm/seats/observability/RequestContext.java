package com.paytm.seats.observability;

import org.slf4j.MDC;

/** MDC keys shared by the request log line and every log statement made while serving a request. */
public final class RequestContext {

    public static final String REQUEST_ID = "request_id";
    public static final String USER_ID = "user_id";
    public static final String SHOW_ID = "show_id";
    public static final String RESERVATION_ID = "reservation_id";
    public static final String OUTCOME = "outcome";
    public static final String REASON = "reason";

    private RequestContext() {
    }

    public static void user(String userId) {
        MDC.put(USER_ID, userId);
    }

    public static void show(Object showId) {
        MDC.put(SHOW_ID, String.valueOf(showId));
    }

    public static void reservation(Object reservationId) {
        MDC.put(RESERVATION_ID, String.valueOf(reservationId));
    }

    public static void outcome(String outcome, String reason) {
        MDC.put(OUTCOME, outcome);
        if (reason != null) {
            MDC.put(REASON, reason);
        }
    }
}
