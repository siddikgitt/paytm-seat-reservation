package com.paytm.seats.show;

public enum SeatStatus {
    AVAILABLE, HELD, CONFIRMED;

    public String db() {
        return name().toLowerCase();
    }

    public static SeatStatus fromDb(String value) {
        return valueOf(value.toUpperCase());
    }
}
