package br.furb.xadrez.partidas.dominio;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import chess.ChessException;
import chess.ChessMatch;
import chess.ChessPiece;
import chess.ChessPosition;
import chess.Color;

/**
 * Jogo em si, usando o motor de xadrez original (pacotes boardgame e chess).
 * O estado nao fica na memoria: a partida e reconstruida repetindo as jogadas gravadas no banco,
 * entao o servico pode reiniciar a qualquer momento sem perder partidas.
 */
@Service
public class JogoService {

    private final JdbcTemplate jdbc;
    private final SagaService saga;

    public JogoService(JdbcTemplate jdbc, SagaService saga) {
        this.jdbc = jdbc;
        this.saga = saga;
    }

    private record Jogada(String origem, String destino, String promocao) {
        String lance() {
            return origem + destino + (promocao == null ? "" : promocao);
        }
    }

    private List<Jogada> jogadas(UUID id) {
        return jdbc.query("SELECT origem, destino, promocao FROM jogadas WHERE partida_id = ? ORDER BY numero",
                (rs, i) -> new Jogada(rs.getString(1), rs.getString(2), rs.getString(3)), id);
    }

    private static ChessPosition pos(String s) {
        if (s == null || !s.matches("[a-h][1-8]")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "posicao invalida: " + s + " (use a1..h8)");
        }
        return new ChessPosition(s.charAt(0), Integer.parseInt(s.substring(1)));
    }

    private static ChessMatch reconstruir(List<Jogada> jogadas) {
        ChessMatch m = new ChessMatch();
        for (Jogada j : jogadas) {
            m.performChessMove(pos(j.origem()), pos(j.destino()));
            if (m.getPromoted() != null) {
                m.replacePromotedPiece(j.promocao() == null ? "Q" : j.promocao());
            }
        }
        return m;
    }

    private Partida buscar(UUID id) {
        List<Partida> l = jdbc.query("SELECT * FROM partidas WHERE id = ?", Partida::map, id);
        if (l.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "partida nao encontrada");
        }
        return l.get(0);
    }

    public Map<String, Object> estado(UUID id) {
        Partida p = buscar(id);
        List<Jogada> js = jogadas(id);
        return montarEstado(p, reconstruir(js), js);
    }

    public List<String> movimentos(UUID id, String origem) {
        ChessMatch m = reconstruir(jogadas(id));
        boolean[][] mat;
        try {
            mat = m.possibleMoves(pos(origem));
        } catch (ChessException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        List<String> destinos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            for (int j = 0; j < 8; j++) {
                if (mat[i][j]) {
                    destinos.add("" + (char) ('a' + j) + (8 - i));
                }
            }
        }
        return destinos;
    }

    @Transactional
    public Map<String, Object> jogar(UUID id, String jogadorId, String origem, String destino, String promocao) {
        Partida p = saga.travar(id); // FOR UPDATE: duas jogadas simultaneas na mesma partida sao serializadas
        if (p == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "partida nao encontrada");
        }
        if (!Partida.EM_ANDAMENTO.equals(p.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "partida nao esta em andamento (" + p.status() + ")");
        }
        if (!p.jogadores().contains(jogadorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "voce nao joga esta partida");
        }
        List<Jogada> js = jogadas(id);
        ChessMatch m = reconstruir(js);
        Color cor = jogadorId.equals(p.brancas()) ? Color.WHITE : Color.BLACK;
        if (m.getCurrentPlayer() != cor) {
            throw new ApiException(HttpStatus.CONFLICT, "nao e a sua vez");
        }
        String promo = promocao == null || promocao.isBlank() ? null : promocao.trim().toUpperCase();
        if (promo != null && !promo.matches("[BNRQ]")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "promocao deve ser B, N, R ou Q");
        }
        try {
            m.performChessMove(pos(origem), pos(destino));
        } catch (ChessException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        String promoUsada = null;
        if (m.getPromoted() != null) {
            promoUsada = promo == null ? "Q" : promo;
            m.replacePromotedPiece(promoUsada);
        }
        jdbc.update("INSERT INTO jogadas (partida_id, numero, jogador_id, origem, destino, promocao) VALUES (?, ?, ?, ?, ?, ?)",
                id, js.size() + 1, jogadorId, origem, destino, promoUsada);
        js = new ArrayList<>(js);
        js.add(new Jogada(origem, destino, promoUsada));

        List<String> lances = js.stream().map(Jogada::lance).toList();
        if (m.getCheckMate()) {
            saga.finalizar(p, jogadorId, "XEQUE_MATE", lances);
            p = buscar(id);
        } else if (m.isStalemate()) {
            saga.finalizar(p, null, "AFOGAMENTO", lances);
            p = buscar(id);
        }
        return montarEstado(p, m, js);
    }

    @Transactional
    public Map<String, Object> desistir(UUID id, String jogadorId) {
        Partida p = saga.travar(id);
        if (p == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "partida nao encontrada");
        }
        if (!Partida.EM_ANDAMENTO.equals(p.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "partida nao esta em andamento (" + p.status() + ")");
        }
        if (!p.jogadores().contains(jogadorId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "voce nao joga esta partida");
        }
        List<Jogada> js = jogadas(id);
        saga.finalizar(p, p.adversarioDe(jogadorId), "DESISTENCIA", js.stream().map(Jogada::lance).toList());
        return montarEstado(buscar(id), reconstruir(js), js);
    }

    private Map<String, Object> montarEstado(Partida p, ChessMatch m, List<Jogada> js) {
        ChessPiece[][] pecas = m.getPieces();
        List<List<String>> tabuleiro = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            List<String> linha = new ArrayList<>();
            for (int j = 0; j < 8; j++) {
                ChessPiece pc = pecas[i][j];
                linha.add(pc == null ? null : (pc.getColor() == Color.WHITE ? "w" : "b") + pc);
            }
            tabuleiro.add(linha);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("partidaId", p.id());
        out.put("status", p.status());
        out.put("motivo", p.motivo());
        out.put("brancas", p.brancas());
        out.put("pretas", p.pretas());
        out.put("aposta", p.aposta());
        out.put("sala", p.salaId());
        out.put("servidorId", p.servidorId());
        out.put("vencedor", p.vencedor());
        out.put("turno", m.getTurn());
        out.put("vez", m.getCurrentPlayer() == Color.WHITE ? p.brancas() : p.pretas());
        out.put("xeque", m.getCheck());
        out.put("xequeMate", m.getCheckMate());
        out.put("lances", js.stream().map(Jogada::lance).toList());
        out.put("tabuleiro", tabuleiro);
        return out;
    }
}
