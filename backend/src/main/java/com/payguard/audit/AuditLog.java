package com.payguard.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Append-only audit trail (a DB trigger rejects UPDATE/DELETE). Writes join the caller's
 * transaction, so an action and its audit record commit together or not at all.
 */
@Repository
public class AuditLog {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public AuditLog(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public record Entry(long id, String actor, String action, String targetType, String targetId, String reason,
                        String beforeState, String afterState, String correlationId, OffsetDateTime createdAt) {
    }

    public void record(String actor, String action, String targetType, String targetId, String reason,
                       Object before, Object after) {
        jdbc.sql("""
                INSERT INTO audit_logs (actor, action, target_type, target_id, reason, before_state, after_state, correlation_id)
                VALUES (:actor, :action, :type, :id, :reason, CAST(:before AS jsonb), CAST(:after AS jsonb), :cid)
                """)
                .param("actor", actor)
                .param("action", action)
                .param("type", targetType)
                .param("id", targetId)
                .param("reason", reason == null ? null : reason.substring(0, Math.min(reason.length(), 500)))
                .param("before", toJson(before))
                .param("after", toJson(after))
                .param("cid", MDC.get("correlationId"))
                .update();
    }

    public List<Entry> recent(String targetType, String targetId, int limit) {
        return jdbc.sql("""
                SELECT id, actor, action, target_type, target_id, reason, before_state::text AS b, after_state::text AS a,
                       correlation_id, created_at
                  FROM audit_logs
                 WHERE (CAST(:type AS varchar) IS NULL OR target_type = :type)
                   AND (CAST(:tid AS varchar) IS NULL OR target_id = :tid)
                 ORDER BY id DESC LIMIT :limit
                """)
                .param("type", targetType)
                .param("tid", targetId)
                .param("limit", limit)
                .query((rs, n) -> new Entry(rs.getLong("id"), rs.getString("actor"), rs.getString("action"),
                        rs.getString("target_type"), rs.getString("target_id"), rs.getString("reason"),
                        rs.getString("b"), rs.getString("a"), rs.getString("correlation_id"),
                        rs.getObject("created_at", OffsetDateTime.class)))
                .list();
    }

    private String toJson(Object o) {
        if (o == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
