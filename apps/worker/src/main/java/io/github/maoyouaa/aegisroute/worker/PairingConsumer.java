package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.events.ObservationV2;
import io.github.maoyouaa.aegisroute.contracts.schema.EventSchemaValidator;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public final class PairingConsumer {
  private final ObjectMapper mapper;
  private final EventSchemaValidator validator;
  private final DurableEvidenceStore store;
  private final TrustedRouteResolver trusted;

  public PairingConsumer(
      ObjectMapper mapper,
      EventSchemaValidator validator,
      DurableEvidenceStore store,
      TrustedRouteResolver trusted) {
    this.mapper = mapper;
    this.validator = validator;
    this.store = store;
    this.trusted = trusted;
  }

  @KafkaListener(topics = "aegis.observation.v2", groupId = "aegis-pairing-v2")
  public void consume(byte[] payload) {
    ObservationV2 event;
    try {
      var tree = mapper.readTree(payload);
      validator.validate("v2/observation.schema.json", tree);
      event = mapper.treeToValue(tree, ObservationV2.class);
    } catch (Exception invalid) {
      store.quarantine("INVALID_OBSERVATION_CONTRACT", payload);
      return;
    }
    if (!trusted.verify(event.sample().route())) {
      store.quarantine("UNTRUSTED_OBSERVATION_ROUTE", payload);
      return;
    }
    store.observed(event, payload);
  }
}
