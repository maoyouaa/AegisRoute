package io.github.maoyouaa.aegisroute.worker;

import io.github.maoyouaa.aegisroute.contracts.events.BaselineObservedV1;
import io.github.maoyouaa.aegisroute.contracts.events.CandidateObservedV1;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ResultPairingStore {
  private final Duration ttl;
  private final Clock clock;
  private final Map<UUID, Partial> samples = new ConcurrentHashMap<>();
  private final Map<UUID, Instant> seenEvents = new ConcurrentHashMap<>();

  public ResultPairingStore(Duration ttl, Clock clock) {
    this.ttl = ttl;
    this.clock = clock;
  }

  public synchronized Optional<Pair> baseline(BaselineObservedV1 event) {
    if (!markSeen(event.eventId())) return Optional.empty();
    Partial current =
        samples.computeIfAbsent(event.sampleId(), ignored -> new Partial(clock.instant()));
    current.baseline = event;
    return current.pair();
  }

  public synchronized Optional<Pair> candidate(CandidateObservedV1 event) {
    if (!markSeen(event.eventId())) return Optional.empty();
    Partial current =
        samples.computeIfAbsent(event.sampleId(), ignored -> new Partial(clock.instant()));
    current.candidate = event;
    return current.pair();
  }

  public synchronized void expire() {
    Instant threshold = clock.instant().minus(ttl);
    samples.entrySet().removeIf(entry -> entry.getValue().createdAt.isBefore(threshold));
    seenEvents.entrySet().removeIf(entry -> entry.getValue().isBefore(threshold));
  }

  int pendingSamples() {
    return samples.size();
  }

  private boolean markSeen(UUID eventId) {
    return seenEvents.putIfAbsent(eventId, clock.instant()) == null;
  }

  private static final class Partial {
    private final Instant createdAt;
    private BaselineObservedV1 baseline;
    private CandidateObservedV1 candidate;

    private Partial(Instant createdAt) {
      this.createdAt = createdAt;
    }

    private Optional<Pair> pair() {
      return baseline == null || candidate == null
          ? Optional.empty()
          : Optional.of(new Pair(baseline, candidate));
    }
  }

  public record Pair(BaselineObservedV1 baseline, CandidateObservedV1 candidate) {}
}
