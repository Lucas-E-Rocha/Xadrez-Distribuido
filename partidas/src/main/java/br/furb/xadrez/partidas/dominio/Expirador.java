package br.furb.xadrez.partidas.dominio;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Cancela (com compensacao) as partidas cujo servidor nao ficou pronto dentro do prazo (F7). */
@Component
public class Expirador {

    private static final Logger log = LoggerFactory.getLogger(Expirador.class);

    private final JdbcTemplate jdbc;
    private final SagaService saga;

    public Expirador(JdbcTemplate jdbc, SagaService saga) {
        this.jdbc = jdbc;
        this.saga = saga;
    }

    @Scheduled(fixedDelay = 1000)
    public void verificar() {
        try {
            List<UUID> vencidas = jdbc.queryForList(
                    "SELECT id FROM partidas WHERE status = 'ALOCANDO_SERVIDOR' AND expira_em <= now() LIMIT 50",
                    UUID.class);
            for (UUID id : vencidas) {
                saga.expirarSeVencida(id);
            }
        } catch (Exception e) {
            log.warn("Falha ao verificar expiracoes: {}", e.getMessage());
        }
    }
}
