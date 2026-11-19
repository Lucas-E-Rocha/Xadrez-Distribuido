package br.furb.xadrez.partidas.dominio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import br.furb.xadrez.partidas.mensageria.Outbox;

/**
 * Forma pares de jogadores com a mesma aposta e reserva uma sala (recurso escasso).
 *
 * R2 / F5: a reserva usa UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED) e a coluna
 * salas.partida_id e UNIQUE. Duas transacoes nunca pegam a mesma sala, mesmo com varias replicas.
 */
@Component
public class Matchmaker {

    private static final Logger log = LoggerFactory.getLogger(Matchmaker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Outbox outbox;
    private final int salasTotal;

    public Matchmaker(JdbcTemplate jdbc, TransactionTemplate tx, Outbox outbox,
                      @Value("${xadrez.salas-total}") int salasTotal) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.outbox = outbox;
        this.salasTotal = salasTotal;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void criarSalas() {
        jdbc.update("INSERT INTO salas (id) SELECT g FROM generate_series(1, ?) g ON CONFLICT (id) DO NOTHING", salasTotal);
        log.info("{} salas disponiveis para partidas simultaneas", salasTotal);
    }

    @Scheduled(fixedDelay = 300)
    public void parear() {
        try {
            tx.executeWithoutResult(s -> {
                List<Map<String, Object>> aguardando = jdbc.queryForList(
                        "SELECT id, jogador_id, aposta FROM fila_tickets WHERE status = 'AGUARDANDO' "
                                + "ORDER BY criado_em LIMIT 200 FOR UPDATE SKIP LOCKED");
                Map<Integer, List<Map<String, Object>>> porAposta = new LinkedHashMap<>();
                for (Map<String, Object> t : aguardando) {
                    porAposta.computeIfAbsent((Integer) t.get("aposta"), k -> new ArrayList<>()).add(t);
                }
                for (Map.Entry<Integer, List<Map<String, Object>>> e : porAposta.entrySet()) {
                    List<Map<String, Object>> l = e.getValue();
                    for (int i = 0; i + 1 < l.size(); i += 2) {
                        criarPartida(l.get(i), l.get(i + 1), e.getKey());
                    }
                }
            });
        } catch (Exception e) {
            log.warn("Falha no matchmaking: {}", e.getMessage());
        }
    }

    private void criarPartida(Map<String, Object> t1, Map<String, Object> t2, int aposta) {
        UUID partidaId = UUID.randomUUID();
        List<Integer> sala = jdbc.queryForList(
                "UPDATE salas SET partida_id = ? WHERE id = (SELECT id FROM salas WHERE partida_id IS NULL "
                        + "ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING id",
                Integer.class, partidaId);

        if (sala.isEmpty()) {
            jdbc.update("UPDATE fila_tickets SET status = 'RECUSADO', motivo = 'SEM_SALA_DISPONIVEL', atualizado_em = now() "
                    + "WHERE id IN (?, ?)", t1.get("id"), t2.get("id"));
            log.warn("Sem sala livre: {} e {} recusados", t1.get("jogador_id"), t2.get("jogador_id"));
            return;
        }

        boolean inverte = ThreadLocalRandom.current().nextBoolean();
        String brancas = (String) (inverte ? t2 : t1).get("jogador_id");
        String pretas = (String) (inverte ? t1 : t2).get("jogador_id");

        jdbc.update("INSERT INTO partidas (id, sala_id, jogador_brancas, jogador_pretas, aposta, status) VALUES (?, ?, ?, ?, ?, ?)",
                partidaId, sala.get(0), brancas, pretas, aposta, Partida.PENDENTE_APOSTAS);
        jdbc.update("UPDATE fila_tickets SET status = 'PAREADO', partida_id = ?, atualizado_em = now() WHERE id IN (?, ?)",
                partidaId, t1.get("id"), t2.get("id"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partidaId", partidaId.toString());
        payload.put("jogadores", List.of(brancas, pretas));
        payload.put("valor", aposta);
        outbox.publicar("ApostasSolicitadas", partidaId.toString(), payload);
        log.info("[corr={}] Par formado: {} (brancas) x {} (pretas), sala {}, aposta {}", partidaId, brancas, pretas,
                sala.get(0), aposta);
    }
}
