package io.github.maoyouaa.aegisroute.gateway.shadow;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class BoundedShadowQueue {
  private final ArrayBlockingQueue<ShadowEnvelope> queue;
  private final long maxBytes;
  private final AtomicLong queuedBytes = new AtomicLong();
  private final Map<ShadowDropReason, Counter> drops = new EnumMap<>(ShadowDropReason.class);
  private volatile boolean accepting = true;

  public BoundedShadowQueue(int maxMessages, long maxBytes, MeterRegistry registry) {
    if (maxMessages < 1 || maxBytes < 1)
      throw new IllegalArgumentException("queue bounds must be positive");
    this.queue = new ArrayBlockingQueue<>(maxMessages);
    this.maxBytes = maxBytes;
    for (ShadowDropReason reason : ShadowDropReason.values()) {
      drops.put(
          reason,
          Counter.builder("aegis.shadow.dropped")
              .tag("reason", reason.metricValue())
              .register(registry));
    }
  }

  public boolean offer(ShadowEnvelope envelope) {
    if (!accepting) return drop(ShadowDropReason.SHUTDOWN);
    if (envelope.size() > maxBytes) return drop(ShadowDropReason.PAYLOAD_TOO_LARGE);
    while (true) {
      long existing = queuedBytes.get();
      if (existing + envelope.size() > maxBytes) return drop(ShadowDropReason.LOCAL_QUEUE_FULL);
      if (queuedBytes.compareAndSet(existing, existing + envelope.size())) break;
    }
    if (!queue.offer(envelope)) {
      queuedBytes.addAndGet(-envelope.size());
      return drop(ShadowDropReason.LOCAL_QUEUE_FULL);
    }
    return true;
  }

  public ShadowEnvelope poll() {
    ShadowEnvelope envelope = queue.poll();
    if (envelope != null) queuedBytes.addAndGet(-envelope.size());
    return envelope;
  }

  public void recordDrop(ShadowDropReason reason) {
    drops.get(reason).increment();
  }

  public int size() {
    return queue.size();
  }

  public long queuedBytes() {
    return queuedBytes.get();
  }

  public void stopAccepting() {
    accepting = false;
  }

  private boolean drop(ShadowDropReason reason) {
    recordDrop(reason);
    return false;
  }
}
