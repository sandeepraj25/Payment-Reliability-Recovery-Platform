package com.payguard.simulation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Persists simulation timelines step by step, so the UI can watch a run progress live. */
@Repository
public class SimulationRecorder {

    public record Step(String phase, String title, String detail, Map<String, Object> evidence, Instant at, long elapsedMs) {
    }

    public record Invariant(String name, boolean held, String detail) {
    }

    public record Run(long id, String scenario, String status, String startedBy, String steps, String invariants,
                      String error, OffsetDateTime startedAt, OffsetDateTime finishedAt) {
    }

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public SimulationRecorder(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public long start(Scenario s, String actor) {
        return jdbc.sql("INSERT INTO simulation_runs (scenario, status, started_by) VALUES (:s, 'RUNNING', :a) RETURNING id")
                .param("s", s.name()).param("a", actor).query(Long.class).single();
    }

    public void appendStep(long runId, Step step) {
        jdbc.sql("UPDATE simulation_runs SET steps = steps || CAST(:step AS jsonb) WHERE id = :id")
                .param("step", json(List.of(step))).param("id", runId).update();
    }

    public void finish(long runId, List<Invariant> invariants, String error) {
        String status = error != null ? "ERROR" : invariants.stream().allMatch(Invariant::held) ? "PASSED" : "FAILED";
        jdbc.sql("UPDATE simulation_runs SET status = :st, invariants = CAST(:inv AS jsonb), error = :err, finished_at = now() WHERE id = :id")
                .param("st", status).param("inv", json(invariants))
                .param("err", error == null ? null : error.substring(0, Math.min(error.length(), 1000)))
                .param("id", runId).update();
    }

    public Optional<Run> get(long id) {
        return jdbc.sql("SELECT id, scenario, status, started_by, steps::text s, invariants::text i, error, started_at, finished_at FROM simulation_runs WHERE id = :id")
                .param("id", id).query((rs, n) -> map(rs)).optional();
    }

    public List<Run> recent(String scenario, int limit) {
        return jdbc.sql("""
                SELECT id, scenario, status, started_by, steps::text s, invariants::text i, error, started_at, finished_at
                  FROM simulation_runs WHERE (CAST(:sc AS varchar) IS NULL OR scenario = :sc)
                 ORDER BY id DESC LIMIT :l
                """).param("sc", scenario).param("l", limit).query((rs, n) -> map(rs)).list();
    }

    private static Run map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Run(rs.getLong("id"), rs.getString("scenario"), rs.getString("status"), rs.getString("started_by"),
                rs.getString("s"), rs.getString("i"), rs.getString("error"),
                rs.getObject("started_at", OffsetDateTime.class), rs.getObject("finished_at", OffsetDateTime.class));
    }

    private String json(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
