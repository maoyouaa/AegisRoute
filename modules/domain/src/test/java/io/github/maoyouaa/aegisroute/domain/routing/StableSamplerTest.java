package io.github.maoyouaa.aegisroute.domain.routing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class StableSamplerTest {
  @Test
  void selectionIsStableAndHonorsBoundaries() {
    boolean selected = StableSampler.selectsCandidate("request-42", 10);
    assertThat(StableSampler.selectsCandidate("request-42", 10)).isEqualTo(selected);
    assertThat(StableSampler.selectsCandidate("request-42", 0)).isFalse();
    assertThat(StableSampler.selectsCandidate("request-42", 100)).isTrue();
  }
}
