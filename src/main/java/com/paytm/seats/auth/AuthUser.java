package com.paytm.seats.auth;

/** The caller as established by a verified token. Never constructed from request bodies. */
public record AuthUser(String userId, boolean admin) {
}
