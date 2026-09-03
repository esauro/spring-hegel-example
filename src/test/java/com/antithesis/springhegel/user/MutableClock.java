package com.antithesis.springhegel.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that only moves when a test tells it to, so expiry is a deterministic property. */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant start) {
        this.now = start;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }
}
