package io.github.maoyouaa.aegisroute.contracts.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.stream.Collectors;

public final class EventSchemaValidator {
  private final ObjectMapper objectMapper;
  private final JsonSchemaFactory schemaFactory;

  public EventSchemaValidator(ObjectMapper objectMapper) {
    this.objectMapper =
        objectMapper
            .copy()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  }

  public byte[] serializeAndValidate(String schemaFile, Object event) {
    try {
      byte[] json = objectMapper.writeValueAsBytes(event);
      validate(schemaFile, objectMapper.readTree(json));
      return json;
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot serialize event", e);
    }
  }

  public JsonNode readAndValidate(String schemaFile, InputStream fixture) {
    try {
      JsonNode json = objectMapper.readTree(fixture);
      validate(schemaFile, json);
      return json;
    } catch (IOException e) {
      throw new IllegalArgumentException("Cannot read event fixture", e);
    }
  }

  public void validate(String schemaFile, JsonNode event) {
    String resource = "events/v1/" + schemaFile;
    try (InputStream schemaStream = resource(resource)) {
      Set<ValidationMessage> messages = schemaFactory.getSchema(schemaStream).validate(event);
      if (!messages.isEmpty()) {
        throw new EventContractViolationException(
            messages.stream()
                .map(ValidationMessage::getMessage)
                .sorted()
                .collect(Collectors.joining("; ")));
      }
    } catch (IOException e) {
      throw new IllegalStateException("Cannot close schema resource " + resource, e);
    }
  }

  private InputStream resource(String path) {
    InputStream stream = EventSchemaValidator.class.getClassLoader().getResourceAsStream(path);
    if (stream == null) throw new IllegalArgumentException("Missing contract resource " + path);
    return stream;
  }
}
