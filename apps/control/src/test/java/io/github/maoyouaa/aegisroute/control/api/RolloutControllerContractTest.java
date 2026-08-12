package io.github.maoyouaa.aegisroute.control.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.maoyouaa.aegisroute.control.service.RolloutService;
import io.github.maoyouaa.aegisroute.domain.rollout.RolloutState;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RolloutController.class)
class RolloutControllerContractTest {
  private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Autowired MockMvc mvc;
  @MockitoBean RolloutService service;

  @Test
  void mutationWithoutIfMatchReturns428() throws Exception {
    mvc.perform(
            post("/api/v1/rollouts/{id}/pause", ID)
                .header("Idempotency-Key", "key-1")
                .contentType("application/json")
                .content("{\"actor\":\"maintainer\",\"reason\":\"test\"}"))
        .andExpect(status().is(428))
        .andExpect(jsonPath("$.error.code").value("PRECONDITION_REQUIRED"));
  }

  @Test
  void successfulMutationReturnsStrongVersionEtag() throws Exception {
    when(service.mutate(eq(ID), eq("pause"), eq("key-1"), eq("\"3\""), any()))
        .thenReturn(
            new RolloutResponse(
                ID, "demo", RolloutState.PAUSED, 4, 0, 9, Instant.parse("2026-08-12T00:00:00Z")));
    mvc.perform(
            post("/api/v1/rollouts/{id}/pause", ID)
                .header("Idempotency-Key", "key-1")
                .header("If-Match", "\"3\"")
                .contentType("application/json")
                .content("{\"actor\":\"maintainer\",\"reason\":\"test\"}"))
        .andExpect(status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string("ETag", "\"4\""));
  }
}
