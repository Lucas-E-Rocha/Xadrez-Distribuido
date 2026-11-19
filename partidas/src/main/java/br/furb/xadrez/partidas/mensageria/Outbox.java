package br.furb.xadrez.partidas.mensageria;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Transactional outbox: o evento e gravado na mesma transacao da mudanca de estado.
 * O {@link OutboxRelay} publica depois. Se o broker estiver fora, os eventos ficam aqui
 * e sao drenados quando ele voltar (F6).
 */
@Component
public class Outbox {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public Outbox(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String publicar(String type, String correlationId, Map<String, ?> payload) {
        String messageId = UUID.randomUUID().toString();
        ObjectNode env = mapper.createObjectNode();
        env.put("messageId", messageId);
        env.put("correlationId", correlationId);
        env.put("type", type);
        env.put("version", 1);
        env.put("occurredAt", Instant.now().toString());
        env.set("payload", mapper.valueToTree(payload));
        try {
            jdbc.update("INSERT INTO outbox (message_id, type, envelope) VALUES (?, ?, ?)", messageId, type,
                    mapper.writeValueAsString(env));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return messageId;
    }
}
