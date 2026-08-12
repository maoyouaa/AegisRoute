package io.github.maoyouaa.aegisroute.gateway.shadow;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ShadowPublisher {
  private final BoundedShadowQueue queue;
  private final KafkaTemplate<String, byte[]> kafkaTemplate;
  private final Duration deliveryTimeout;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService publisher =
      Executors.newSingleThreadExecutor(
          runnable -> Thread.ofPlatform().name("aegis-shadow-publisher").unstarted(runnable));

  public ShadowPublisher(
      BoundedShadowQueue queue,
      KafkaTemplate<String, byte[]> kafkaTemplate,
      @Value("${aegis.shadow.delivery-timeout:2s}") Duration deliveryTimeout) {
    this.queue = queue;
    this.kafkaTemplate = kafkaTemplate;
    this.deliveryTimeout = deliveryTimeout;
  }

  @PostConstruct
  void start() {
    running.set(true);
    publisher.submit(this::publishLoop);
  }

  @PreDestroy
  void stop() {
    queue.stopAccepting();
    running.set(false);
    publisher.shutdownNow();
    try {
      publisher.awaitTermination(2, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
    while (queue.poll() != null) queue.recordDrop(ShadowDropReason.SHUTDOWN);
  }

  private void publishLoop() {
    while (running.get()) {
      ShadowEnvelope envelope = queue.poll();
      if (envelope == null) {
        try {
          Thread.sleep(5);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          return;
        }
        continue;
      }
      publishWithBoundedRetry(envelope);
    }
  }

  private void publishWithBoundedRetry(ShadowEnvelope envelope) {
    for (int attempt = 0; attempt < 2 && running.get(); attempt++) {
      try {
        CompletableFuture<?> send =
            kafkaTemplate.send(
                new ProducerRecord<>(envelope.topic(), envelope.key(), envelope.payload()));
        send.get(deliveryTimeout.toMillis(), TimeUnit.MILLISECONDS);
        return;
      } catch (TimeoutException timeout) {
        if (attempt == 1) queue.recordDrop(ShadowDropReason.PUBLISHER_TIMEOUT);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        queue.recordDrop(ShadowDropReason.SHUTDOWN);
        return;
      } catch (Exception unavailable) {
        if (attempt == 1) queue.recordDrop(ShadowDropReason.BROKER_UNAVAILABLE);
      }
    }
  }
}
