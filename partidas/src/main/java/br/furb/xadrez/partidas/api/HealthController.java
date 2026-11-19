package br.furb.xadrez.partidas.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            Long pendentes = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE enviado_em IS NULL", Long.class);
            return ResponseEntity.ok(Map.of("status", "UP", "servico", "partidas", "outboxPendente", pendentes));
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "erro", String.valueOf(e.getMessage())));
        }
    }
}
