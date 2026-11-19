package br.furb.xadrez.partidas.dominio;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Partida(UUID id, Integer salaId, String brancas, String pretas, int aposta, String status,
                      String motivo, String vencedor, String servidorId, Instant expiraEm) {

    public static final String PENDENTE_APOSTAS = "PENDENTE_APOSTAS";
    public static final String ALOCANDO_SERVIDOR = "ALOCANDO_SERVIDOR";
    public static final String EM_ANDAMENTO = "EM_ANDAMENTO";
    public static final String FINALIZADA = "FINALIZADA";
    public static final String CANCELADA = "CANCELADA";

    public static Partida map(ResultSet rs, int i) throws SQLException {
        Timestamp exp = rs.getTimestamp("expira_em");
        Integer sala = (Integer) rs.getObject("sala_id");
        return new Partida(rs.getObject("id", UUID.class), sala,
                rs.getString("jogador_brancas"), rs.getString("jogador_pretas"), rs.getInt("aposta"),
                rs.getString("status"), rs.getString("motivo"), rs.getString("vencedor"),
                rs.getString("servidor_id"), exp == null ? null : exp.toInstant());
    }

    public List<String> jogadores() {
        return List.of(brancas, pretas);
    }

    public String adversarioDe(String jogador) {
        return jogador.equals(brancas) ? pretas : brancas;
    }
}
