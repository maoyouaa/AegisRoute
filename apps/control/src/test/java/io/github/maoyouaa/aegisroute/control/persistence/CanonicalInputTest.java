package io.github.maoyouaa.aegisroute.control.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalInputTest {
  @Test
  void endpointParticipatesInIdempotencyIdentity() {
    CanonicalInput input = new CanonicalInput(new ObjectMapper());
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("actor", "maintainer");
    payload.put("reason", "test");

    assertThat(input.hash("pause", payload)).isNotEqualTo(input.hash("rollback", payload));
    assertThat(input.hash("pause", payload)).isEqualTo(input.hash("pause", payload));
  }
}
