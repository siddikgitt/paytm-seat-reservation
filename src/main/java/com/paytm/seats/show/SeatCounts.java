package com.paytm.seats.show;

/** Reconciliation invariant: available + held + confirmed == total. */
public record SeatCounts(long available, long held, long confirmed, long total) {

    public boolean reconciles() {
        return available + held + confirmed == total;
    }
}
