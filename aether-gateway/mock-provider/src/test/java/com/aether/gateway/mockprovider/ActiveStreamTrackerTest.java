package com.aether.gateway.mockprovider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ActiveStreamTrackerTest {

    @Test
    void startsAtZero() {
        assertThat(new ActiveStreamTracker().count()).isZero();
    }

    @Test
    void incrementsOnStart() {
        var tracker = new ActiveStreamTracker();

        tracker.streamStarted();

        assertThat(tracker.count()).isEqualTo(1);
    }

    @Test
    void decrementsOnFinish() {
        var tracker = new ActiveStreamTracker();
        tracker.streamStarted();

        tracker.streamFinished();

        assertThat(tracker.count()).isZero();
    }

    @Test
    void tracksMultipleConcurrentStreamsIndependently() {
        var tracker = new ActiveStreamTracker();
        tracker.streamStarted();
        tracker.streamStarted();
        tracker.streamStarted();

        tracker.streamFinished();

        assertThat(tracker.count()).isEqualTo(2);
    }
}
