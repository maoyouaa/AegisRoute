package io.github.maoyouaa.aegisroute.worker;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
    properties = {
      "aegis.control-base-url=http://127.0.0.1:1",
      "aegis.candidate-base-url=http://127.0.0.1:1",
      "spring.kafka.bootstrap-servers=127.0.0.1:1",
      "spring.kafka.listener.auto-startup=false"
    })
class WorkerApplicationContextTest {
  @Test
  void loadsCompleteApplicationContext() {}
}
