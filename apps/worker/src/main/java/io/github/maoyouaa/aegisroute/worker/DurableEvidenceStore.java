package io.github.maoyouaa.aegisroute.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.maoyouaa.aegisroute.contracts.events.*;
import io.github.maoyouaa.aegisroute.domain.routing.RouteChecksum;
import io.github.maoyouaa.aegisroute.domain.routing.RouteSnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Single-writer durable inbox/outbox. No network or provider call may run inside a transaction. */
public final class DurableEvidenceStore implements AutoCloseable {
  private final Connection db;
  private final ObjectMapper mapper;

  public DurableEvidenceStore(Path file, ObjectMapper mapper) {
    this.mapper = mapper;
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      execute("PRAGMA journal_mode=WAL");
      execute("PRAGMA synchronous=FULL");
      execute("PRAGMA busy_timeout=5000");
      execute(
          """
          CREATE TABLE IF NOT EXISTS samples (
            sample_key TEXT PRIMARY KEY, identity TEXT NOT NULL, window_id TEXT NOT NULL,
            window_start INTEGER NOT NULL, route TEXT NOT NULL, requested BLOB, result BLOB,
            result_published INTEGER NOT NULL DEFAULT 0, baseline BLOB, canary BLOB, shadow BLOB)
          """);
      execute("CREATE INDEX IF NOT EXISTS sample_window ON samples(window_id)");
      execute(
          "CREATE TABLE IF NOT EXISTS records (seq INTEGER PRIMARY KEY AUTOINCREMENT, topic TEXT NOT NULL, payload BLOB NOT NULL)");
      execute(
          "CREATE TABLE IF NOT EXISTS windows (window_id TEXT PRIMARY KEY, payload BLOB NOT NULL, receipt TEXT, sealed_at TEXT NOT NULL, window_start INTEGER NOT NULL)");
      execute(
          "CREATE TABLE IF NOT EXISTS quarantine (digest TEXT PRIMARY KEY, reason TEXT NOT NULL, payload BLOB NOT NULL, created_at TEXT NOT NULL)");
    } catch (Exception unavailable) {
      throw new IllegalStateException("Cannot open durable Worker store", unavailable);
    }
  }

  public synchronized void requested(ShadowRequestedV2 event, byte[] payload) {
    transaction(
        () -> {
          if (!identity(event.sample(), payload)) return;
          byte[] old =
              blob("SELECT requested FROM samples WHERE sample_key=?", event.sample().key());
          if (old != null) {
            var previous = read(old, ShadowRequestedV2.class);
            if (!previous.request().equals(event.request()))
              quarantineInside("REQUEST_CONFLICT", payload);
            return;
          }
          if (sealed(event.sample())) {
            quarantineInside("SEALED_WINDOW_LATE_REQUEST", payload);
            return;
          }
          update(
              "UPDATE samples SET requested=? WHERE sample_key=?", payload, event.sample().key());
          record("aegis.shadow-requested.v2", payload);
        });
  }

  public synchronized void observed(ObservationV2 event, byte[] payload) {
    transaction(
        () -> {
          if (!identity(event.sample(), payload)) return;
          String column =
              switch (event.kind()) {
                case BASELINE -> "baseline";
                case CANARY -> "canary";
                case SHADOW -> "shadow";
              };
          byte[] old =
              blob("SELECT " + column + " FROM samples WHERE sample_key=?", event.sample().key());
          if (old != null) {
            ObservationV2 previous = read(old, ObservationV2.class);
            if (previous.outcome() != event.outcome()
                || previous.statusCode() != event.statusCode()
                || previous.latencyMs() != event.latencyMs())
              quarantineInside("OUTCOME_CONFLICT", payload);
            return;
          }
          if (sealed(event.sample())) {
            quarantineInside("SEALED_WINDOW_LATE_OBSERVATION", payload);
            return;
          }
          update(
              "UPDATE samples SET " + column + "=? WHERE sample_key=?",
              payload,
              event.sample().key());
          record("aegis.observation.v2", payload);
        });
  }

  private boolean identity(SampleIdentity sample, byte[] payload) throws Exception {
    String windowRoute =
        string("SELECT route FROM samples WHERE window_id=? LIMIT 1", sample.windowId().toString());
    if (windowRoute != null
        && !read(windowRoute.getBytes(java.nio.charset.StandardCharsets.UTF_8), RouteSnapshot.class)
            .equals(sample.route())) {
      quarantineInside("WINDOW_ROUTE_MISMATCH", payload);
      return false;
    }
    String existing = string("SELECT identity FROM samples WHERE sample_key=?", sample.key());
    if (existing != null
        && !read(existing.getBytes(java.nio.charset.StandardCharsets.UTF_8), SampleIdentity.class)
            .equals(sample)) {
      quarantineInside("IDENTITY_MISMATCH", payload);
      return false;
    }
    if (sealed(sample)) {
      if (existing != null) return true;
      quarantineInside("SEALED_WINDOW_LATE_SAMPLE", payload);
      return false;
    }
    if (sample.admittedAt().isAfter(Instant.now().plusSeconds(5))) {
      quarantineInside("FUTURE_SAMPLE", payload);
      return false;
    }
    if (existing == null) {
      if (count("samples") >= 200000)
        throw new IllegalStateException("Durable sample capacity reached; ingestion paused");
      update(
          "INSERT INTO samples(sample_key,identity,window_id,window_start,route) VALUES (?,?,?,?,?)",
          sample.key(),
          json(sample),
          sample.windowId().toString(),
          sample.windowStart().toEpochMilli(),
          json(sample.route()));
    }
    return true;
  }

  private boolean sealed(SampleIdentity sample) {
    return string("SELECT window_id FROM windows WHERE window_id=?", sample.windowId().toString())
        != null;
  }

  public synchronized List<Work> pendingExecutions(int limit) {
    return query(
        "SELECT sample_key, requested FROM samples WHERE requested IS NOT NULL AND result IS NULL AND window_id NOT IN (SELECT window_id FROM windows) ORDER BY window_start LIMIT ?",
        rs -> new Work(rs.getString(1), read(rs.getBytes(2), ShadowRequestedV2.class)),
        limit);
  }

  /** The seal is the terminal boundary for work that has not begun execution. */
  public synchronized boolean executionOpen(String key) {
    return !query(
            "SELECT sample_key FROM samples WHERE sample_key=? AND result IS NULL AND window_id NOT IN (SELECT window_id FROM windows)",
            rs -> rs.getString(1),
            key)
        .isEmpty();
  }

  public synchronized void saveResult(String key, ObservationV2 result, byte[] bytes) {
    transaction(
        () -> {
          String identity = string("SELECT identity FROM samples WHERE sample_key=?", key);
          if (identity == null
              || !read(
                      identity.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      SampleIdentity.class)
                  .equals(result.sample())) {
            throw new IllegalArgumentException("candidate result identity mismatch");
          }
          if (blob("SELECT result FROM samples WHERE sample_key=?", key) == null) {
            update("UPDATE samples SET result=? WHERE sample_key=?", bytes, key);
            // The durable local result is the pairing fact; broker round-trip timing cannot change
            // it.
            if (!sealed(result.sample())
                && blob("SELECT shadow FROM samples WHERE sample_key=?", key) == null) {
              update("UPDATE samples SET shadow=? WHERE sample_key=?", bytes, key);
              record("aegis.observation.v2", bytes);
            }
          }
        });
  }

  public synchronized List<Publication> pendingResults(int limit) {
    return query(
        "SELECT sample_key, result FROM samples WHERE result IS NOT NULL AND result_published=0 ORDER BY window_start LIMIT ?",
        rs -> new Publication(rs.getString(1), rs.getBytes(2)),
        limit);
  }

  public synchronized void resultPublished(String key) {
    transaction(() -> update("UPDATE samples SET result_published=1 WHERE sample_key=?", key));
  }

  public synchronized List<Publication> pendingWindows() {
    return query(
        "SELECT window_id, payload FROM windows WHERE receipt IS NULL ORDER BY window_start, window_id",
        rs -> new Publication(rs.getString(1), rs.getBytes(2)));
  }

  public synchronized void acknowledgeWindow(String id, String receipt) {
    transaction(
        () -> {
          String previous = string("SELECT receipt FROM windows WHERE window_id=?", id);
          if (previous != null && !previous.equals(receipt))
            throw new IllegalStateException("Control receipt changed");
          update("UPDATE windows SET receipt=? WHERE window_id=? AND receipt IS NULL", receipt, id);
        });
  }

  public synchronized void seal(Instant now, Duration grace) {
    transaction(
        () -> {
          var ids =
              query(
                  """
          SELECT DISTINCT window_id FROM samples WHERE window_start <= ?
          AND window_id NOT IN (SELECT window_id FROM windows) ORDER BY window_start
          """,
                  rs -> rs.getString(1),
                  now.minus(grace).minusSeconds(5).toEpochMilli());
          for (String id : ids) {
            var samples =
                query(
                    "SELECT identity, requested, baseline, canary, shadow, result FROM samples WHERE window_id=? ORDER BY sample_key",
                    rs ->
                        new Sample(
                            read(
                                rs.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                SampleIdentity.class),
                            rs.getBytes(2),
                            observation(rs.getBytes(3)),
                            observation(rs.getBytes(4)),
                            observation(rs.getBytes(5)),
                            rs.getBytes(6)),
                    id);
            SampleIdentity first = samples.getFirst().identity;
            int serving = 0,
                candidate = 0,
                errors = 0,
                pairs = 0,
                baselineErrors = 0,
                shadowErrors = 0,
                unpaired = 0,
                pending = 0;
            StringBuilder digest = new StringBuilder();
            for (Sample sample : samples) {
              digest.append(json(sample.identity)).append('\n');
              if (sample.baseline != null || sample.canary != null) serving++;
              if (sample.baseline != null && sample.baseline.failed()) baselineErrors++;
              if (sample.shadow != null && sample.shadow.failed()) shadowErrors++;
              if (sample.canary != null) {
                candidate++;
                if (sample.canary.failed()) errors++;
              }
              if (sample.identity.shadowSelected()) {
                if (sample.baseline != null && sample.shadow != null) {
                  pairs++;
                } else unpaired++;
                if (sample.requested != null && sample.result == null) pending++;
              }
            }
            var window =
                new EvidenceWindowV2(
                    2,
                    UUID.fromString(id),
                    1,
                    first.route(),
                    first.windowStart(),
                    first.windowStart().plusSeconds(5),
                    serving,
                    candidate,
                    errors,
                    pairs,
                    baselineErrors,
                    shadowErrors,
                    unpaired,
                    pending,
                    RouteChecksum.digest(digest.toString()));
            update(
                "INSERT INTO windows VALUES (?,?,NULL,?,?)",
                id,
                mapper.writeValueAsBytes(window),
                now.toString(),
                first.windowStart().toEpochMilli());
          }
        });
  }

  public synchronized void quarantine(String reason, byte[] bytes) {
    transaction(() -> quarantineInside(reason, bytes));
  }

  private void quarantineInside(String reason, byte[] bytes) throws Exception {
    String hash = RouteChecksum.digest(reason + "/" + Base64.getEncoder().encodeToString(bytes));
    if (string("SELECT digest FROM quarantine WHERE digest=?", hash) != null) return;
    if (count("quarantine") >= 10000)
      throw new IllegalStateException("Quarantine capacity reached; ingestion paused");
    update(
        "INSERT INTO quarantine VALUES (?,?,?,?)",
        hash,
        reason,
        Arrays.copyOf(bytes, Math.min(bytes.length, 131072)),
        Instant.now().toString());
  }

  private void record(String topic, byte[] bytes) throws Exception {
    if (count("records") >= 400000)
      throw new IllegalStateException("Raw record capacity reached; ingestion paused");
    update("INSERT INTO records(topic,payload) VALUES (?,?)", topic, bytes);
  }

  public synchronized long count(String table) {
    if (!Set.of("samples", "records", "quarantine", "windows").contains(table))
      throw new IllegalArgumentException("Unknown store table");
    return query("SELECT count(*) FROM " + table, rs -> rs.getLong(1)).getFirst();
  }

  public synchronized Map<String, Long> statistics() {
    Map<String, Long> result = new LinkedHashMap<>();
    for (String table : List.of("samples", "records", "quarantine", "windows"))
      result.put(table, count(table));
    result.put("pending_windows", pendingCount());
    result.put(
        "pending_executions",
        query(
                "SELECT count(*) FROM samples WHERE requested IS NOT NULL AND result IS NULL AND window_id NOT IN (SELECT window_id FROM windows)",
                rs -> rs.getLong(1))
            .getFirst());
    result.put(
        "expired_executions",
        query(
                "SELECT count(*) FROM samples WHERE requested IS NOT NULL AND result IS NULL AND window_id IN (SELECT window_id FROM windows)",
                rs -> rs.getLong(1))
            .getFirst());
    result.put(
        "pending_results",
        query(
                "SELECT count(*) FROM samples WHERE result IS NOT NULL AND result_published=0",
                rs -> rs.getLong(1))
            .getFirst());
    return result;
  }

  public synchronized long pendingCount() {
    return query("SELECT count(*) FROM windows WHERE receipt IS NULL", rs -> rs.getLong(1))
        .getFirst();
  }

  private ObservationV2 observation(byte[] bytes) {
    return bytes == null ? null : read(bytes, ObservationV2.class);
  }

  private String json(Object value) throws Exception {
    return mapper.writeValueAsString(value);
  }

  public <T> T read(byte[] bytes, Class<T> type) {
    try {
      return mapper.readValue(bytes, type);
    } catch (Exception invalid) {
      throw new IllegalStateException("Cannot read durable payload", invalid);
    }
  }

  private String string(String sql, Object... parameters) {
    var rows = query(sql, rs -> rs.getString(1), parameters);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private byte[] blob(String sql, Object... parameters) {
    var rows = query(sql, rs -> rs.getBytes(1), parameters);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  private void execute(String sql) throws SQLException {
    try (var statement = db.createStatement()) {
      statement.execute(sql);
    }
  }

  private void update(String sql, Object... parameters) throws SQLException {
    try (var statement = db.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      statement.executeUpdate();
    }
  }

  private <T> List<T> query(String sql, Row<T> row, Object... parameters) {
    try (var statement = db.prepareStatement(sql)) {
      for (int i = 0; i < parameters.length; i++) statement.setObject(i + 1, parameters[i]);
      try (var rs = statement.executeQuery()) {
        List<T> result = new ArrayList<>();
        while (rs.next()) result.add(row.read(rs));
        return result;
      }
    } catch (Exception failure) {
      throw new IllegalStateException("Durable store query failed", failure);
    }
  }

  private void transaction(Operation work) {
    try {
      db.setAutoCommit(false);
      work.run();
      db.commit();
    } catch (Exception failure) {
      try {
        db.rollback();
      } catch (SQLException rollback) {
        failure.addSuppressed(rollback);
      }
      throw new IllegalStateException(
          "Durable Worker transaction failed; Kafka record must retry", failure);
    } finally {
      try {
        db.setAutoCommit(true);
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }
  }

  @Override
  public synchronized void close() throws SQLException {
    db.close();
  }

  @FunctionalInterface
  private interface Operation {
    void run() throws Exception;
  }

  @FunctionalInterface
  private interface Row<T> {
    T read(ResultSet rs) throws Exception;
  }

  public record Work(String key, ShadowRequestedV2 request) {}

  public record Publication(String key, byte[] payload) {}

  private record Sample(
      SampleIdentity identity,
      byte[] requested,
      ObservationV2 baseline,
      ObservationV2 canary,
      ObservationV2 shadow,
      byte[] result) {}
}
