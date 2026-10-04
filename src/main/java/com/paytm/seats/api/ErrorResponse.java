package com.paytm.seats.api;

public record ErrorResponse(String error, String reason, String message, String requestId) {
}
