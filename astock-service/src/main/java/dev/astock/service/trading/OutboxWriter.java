package dev.astock.service.trading;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class OutboxWriter {
    private final JdbcTemplate jdbc;

    public OutboxWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void append(String aggregateType, String aggregateId, String eventType, String payloadJson) {
        jdbc.update("""
                INSERT INTO astock_outbox (aggregate_type, aggregate_id, event_type, payload_json)
                VALUES (?, ?, ?, ?)
                """, aggregateType, aggregateId, eventType, payloadJson);
    }
}
