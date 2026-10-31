package com.finkraft.mock.aspa;

import java.util.ArrayDeque;
import java.util.Deque;

/** Requests in the last second: one window for everything that arrived, one for what was admitted. */
class SlidingWindow {

    private final int limit;
    private final Deque<Long> arrived = new ArrayDeque<>();
    private final Deque<Long> admitted = new ArrayDeque<>();
    private int peakArrivedPerSecond;

    SlidingWindow(int limit) {
        this.limit = limit;
    }

    /** Records an arrival; false means the request is over the limit and gets 429. */
    synchronized boolean admit() {
        long now = System.nanoTime() / 1_000_000;
        prune(arrived, now);
        prune(admitted, now);
        arrived.addLast(now);
        peakArrivedPerSecond = Math.max(peakArrivedPerSecond, arrived.size());
        if (admitted.size() >= limit) {
            return false;
        }
        admitted.addLast(now);
        return true;
    }

    synchronized int peak() {
        return peakArrivedPerSecond;
    }

    synchronized void resetPeak() {
        peakArrivedPerSecond = 0;
    }

    private static void prune(Deque<Long> window, long now) {
        while (!window.isEmpty() && window.peekFirst() <= now - 1000) {
            window.removeFirst();
        }
    }
}
