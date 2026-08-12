package io.github.maoyouaa.aegisroute.control.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CanonicalInput {
  private final ObjectMapper objectMapper;

  public CanonicalInput(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public String hash(String endpoint, Object payload) {
    try {
      JsonNode tree = objectMapper.valueToTree(payload);
      String canonical = endpoint + "\n" + objectMapper.writeValueAsString(tree);
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Cannot canonicalize mutation", e);
    }
  }
}
