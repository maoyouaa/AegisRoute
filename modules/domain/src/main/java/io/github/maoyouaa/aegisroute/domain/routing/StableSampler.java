package io.github.maoyouaa.aegisroute.domain.routing;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class StableSampler {
  private StableSampler() {}

  public static boolean selectsCandidate(String requestKey, int candidateRatio) {
    if (candidateRatio <= 0) return false;
    if (candidateRatio >= 100) return true;
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(requestKey.getBytes(StandardCharsets.UTF_8));
      long unsigned = Integer.toUnsignedLong(ByteBuffer.wrap(digest).getInt());
      return unsigned % 10_000 < candidateRatio * 100L;
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("JVM does not provide SHA-256", impossible);
    }
  }
}
