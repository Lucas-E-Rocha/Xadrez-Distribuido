package br.furb.xadrez.partidas.dominio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** R3: entrar na fila so grava o pedido e devolve um id; o pareamento acontece depois, em segundo plano. */
@Service
public class FilaService {

    private final JdbcTemplate jdbc;

    public FilaService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID entrar(String jogadorId, int aposta) {
        if (jogadorId == null || !jogadorId.matches("[A-Za-z0-9_.-]{1,40}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "jogadorId invalido (use letras, numeros, _ . -)");
        }
        if (aposta < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "aposta nao pode ser negativa");
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO fila_tickets (id, jogador_id, aposta, status) VALUES (?, ?, ?, 'AGUARDANDO')",
                    id, jogadorId, aposta);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "jogador " + jogadorId + " ja esta na fila ou em uma partida");
        }
        return id;
    }

    public Map<String, Object> consultar(UUID ticketId) {
        List<Map<String, Object>> l = jdbc.queryForList(
                "SELECT t.id, t.jogador_id, t.aposta, t.status, t.partida_id, t.motivo, p.status AS status_partida "
                        + "FROM fila_tickets t LEFT JOIN partidas p ON p.id = t.partida_id WHERE t.id = ?",
                ticketId);
        if (l.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ticket nao encontrado");
        }
        Map<String, Object> r = l.get(0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticketId", r.get("id"));
        out.put("jogadorId", r.get("jogador_id"));
        out.put("aposta", r.get("aposta"));
        out.put("status", r.get("status"));
        out.put("partidaId", r.get("partida_id"));
        out.put("statusPartida", r.get("status_partida"));
        out.put("motivo", r.get("motivo"));
        return out;
    }
}
