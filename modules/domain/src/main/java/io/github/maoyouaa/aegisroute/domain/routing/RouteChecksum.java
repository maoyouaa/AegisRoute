package io.github.maoyouaa.aegisroute.domain.routing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class RouteChecksum {
  private RouteChecksum() {}

  public static String calculate(
      UUID routeId,
      UUID rolloutId,
      long version,
      String baselineDeploymentId,
      String baselineBaseUrl,
      String candidateDeploymentId,
      String candidateBaseUrl,
      int candidateRatio) {
    var canonical =
        routeId
            + "\n"
            + rolloutId
            + "\n"
            + version
            + "\n"
            + baselineDeploymentId
            + "\n"
            + baselineBaseUrl
            + "\n"
            + candidateDeploymentId
            + "\n"
            + (candidateBaseUrl == null ? "" : candidateBaseUrl)
            + "\n"
            + candidateRatio;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }
}
