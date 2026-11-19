package br.furb.xadrez.partidas.dominio;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import br.furb.xadrez.partidas.config.ConfiguracaoDinamica;
import br.furb.xadrez.partidas.mensageria.Envelope;
import br.furb.xadrez.partidas.mensageria.Outbox;

/**
 * Orquestrador da saga "partida ranqueada valendo moedas".
 *
 * PENDENTE_APOSTAS --ApostasDebitadas--> ALOCANDO_SERVIDOR --ServidorAlocado--> EM_ANDAMENTO --> FINALIZADA
 *        |                                      |  (falha ou timeout)
 *        +--ApostasRecusadas--> CANCELADA <------+  compensacao: libera sala + PartidaCancelada (Carteira estorna)
 *
 * Cada transicao confere o estado atual: eventos fora de ordem ou atrasados nao quebram a saga.
 */
@Service
public class SagaService {

    private static final Logger log = LoggerFactory.getLogger(SagaService.class);

    private final JdbcTemplate jdbc;
    private final Outbox outbox;
    private final ConfiguracaoDinamica config;

    public SagaService(JdbcTemplate jdbc, Outbox outbox, ConfiguracaoDinamica config) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.config = config;
    }

    public Partida travar(UUID id) {
        List<Partida> l = jdbc.query("SELECT * FROM partidas WHERE id = ? FOR UPDATE", Partida::map, id);
        return l.isEmpty() ? null : l.get(0);
    }

    private Partida partidaDoEvento(Envelope env) {
        UUID id;
        try {
            id = UUID.fromString(env.texto("partidaId"));
        } catch (IllegalArgumentException e) {
            throw new br.furb.xadrez.partidas.mensageria.MensagemInvalidaException("partidaId invalido");
        }
        Partida p = travar(id);
        if (p == null) {
            log.warn("[corr={}] {} para partida desconhecida; ignorado", env.correlationId(), env.type());
        }
        return p;
    }

    // ---------- passo 2: Carteira respondeu ----------

    @Transactional(propagation = Propagation.MANDATORY)
    public void aoDebitarApostas(Envelope env) {
        Partida p = partidaDoEvento(env);
        if (p == null) return;
        if (!Partida.PENDENTE_APOSTAS.equals(p.status())) {
            log.info("[corr={}] ApostasDebitadas com partida em {}; nada a fazer", p.id(), p.status());
            return;
        }
        int timeout = config.getTimeoutAlocacaoSegundos();
        jdbc.update("UPDATE partidas SET status = ?, expira_em = now() + make_interval(secs => ?), atualizado_em = now() WHERE id = ?",
                Partida.ALOCANDO_SERVIDOR, timeout, p.id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partidaId", p.id().toString());
        payload.put("salaId", p.salaId());
        payload.put("timeoutSegundos", timeout);
        outbox.publicar("AlocacaoSolicitada", p.id().toString(), payload);
        log.info("[corr={}] Apostas debitadas; pedindo servidor de jogo (timeout {}s)", p.id(), timeout);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void aoRecusarApostas(Envelope env) {
        Partida p = partidaDoEvento(env);
        if (p == null) return;
        if (Partida.PENDENTE_APOSTAS.equals(p.status())) {
            String motivo = env.payload().path("motivo").asText("APOSTAS_RECUSADAS");
            cancelar(p, "APOSTAS_RECUSADAS: " + motivo, false);
        }
    }

    // ---------- passo 3: dependencia instavel (alocador) respondeu ----------

    @Transactional(propagation = Propagation.MANDATORY)
    public void aoAlocarServidor(Envelope env) {
        Partida p = partidaDoEvento(env);
        if (p == null) return;
        String servidorId = env.texto("servidorId");
        boolean noPrazo = p.expiraEm() != null && Instant.now().isBefore(p.expiraEm());

        if (Partida.ALOCANDO_SERVIDOR.equals(p.status()) && noPrazo) {
            jdbc.update("UPDATE partidas SET status = ?, servidor_id = ?, atualizado_em = now() WHERE id = ?",
                    Partida.EM_ANDAMENTO, servidorId, p.id());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("partidaId", p.id().toString());
            payload.put("brancas", p.brancas());
            payload.put("pretas", p.pretas());
            payload.put("aposta", p.aposta());
            payload.put("servidorId", servidorId);
            outbox.publicar("PartidaIniciada", p.id().toString(), payload);
            log.info("[corr={}] Servidor {} alocado: partida EM_ANDAMENTO", p.id(), servidorId);
            return;
        }

        // F7: resposta tardia. Se ainda nao tinha expirado oficialmente, expira agora.
        if (Partida.ALOCANDO_SERVIDOR.equals(p.status())) {
            cancelar(p, "TIMEOUT_ALOCACAO", true);
        }
        log.warn("[corr={}] ServidorAlocado chegou atrasado (partida {}): pedindo liberacao do servidor {}",
                p.id(), Partida.ALOCANDO_SERVIDOR.equals(p.status()) ? Partida.CANCELADA : p.status(), servidorId);
        liberarServidor(p.id(), servidorId, "RESULTADO_TARDIO");
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void aoFalharAlocacao(Envelope env) {
        Partida p = partidaDoEvento(env);
        if (p == null) return;
        if (Partida.ALOCANDO_SERVIDOR.equals(p.status())) {
            String motivo = env.payload().path("motivo").asText("erro");
            cancelar(p, "FALHA_ALOCACAO: " + motivo, true);
        }
    }

    /** Chamado pelo {@link Expirador}. */
    @Transactional
    public void expirarSeVencida(UUID id) {
        Partida p = travar(id);
        if (p != null && Partida.ALOCANDO_SERVIDOR.equals(p.status()) && p.expiraEm() != null
                && !Instant.now().isBefore(p.expiraEm())) {
            cancelar(p, "TIMEOUT_ALOCACAO", true);
        }
    }

    // ---------- compensacao ----------

    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelar(Partida p, String motivo, boolean apostasDebitadas) {
        jdbc.update("UPDATE partidas SET status = ?, motivo = ?, atualizado_em = now() WHERE id = ?",
                Partida.CANCELADA, motivo, p.id());
        jdbc.update("UPDATE salas SET partida_id = NULL WHERE partida_id = ?", p.id());
        jdbc.update("UPDATE fila_tickets SET status = 'CANCELADO', motivo = ?, atualizado_em = now() WHERE partida_id = ?",
                motivo, p.id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partidaId", p.id().toString());
        payload.put("jogadores", p.jogadores());
        payload.put("valor", p.aposta());
        payload.put("apostasDebitadas", apostasDebitadas);
        payload.put("motivo", motivo);
        outbox.publicar("PartidaCancelada", p.id().toString(), payload);
        log.warn("[corr={}] COMPENSACAO: partida cancelada ({}), sala {} liberada{}", p.id(), motivo, p.salaId(),
                apostasDebitadas ? ", Carteira vai estornar as apostas" : "");
    }

    // ---------- fim de jogo ----------

    @Transactional(propagation = Propagation.MANDATORY)
    public void finalizar(Partida p, String vencedor, String motivo, List<String> lances) {
        jdbc.update("UPDATE partidas SET status = ?, vencedor = ?, motivo = ?, atualizado_em = now() WHERE id = ?",
                Partida.FINALIZADA, vencedor, motivo, p.id());
        jdbc.update("UPDATE salas SET partida_id = NULL WHERE partida_id = ?", p.id());
        jdbc.update("UPDATE fila_tickets SET status = 'CONCLUIDO', atualizado_em = now() WHERE partida_id = ?", p.id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partidaId", p.id().toString());
        payload.put("brancas", p.brancas());
        payload.put("pretas", p.pretas());
        payload.put("jogadores", p.jogadores());
        payload.put("vencedor", vencedor);
        payload.put("perdedor", vencedor == null ? null : p.adversarioDe(vencedor));
        payload.put("empate", vencedor == null);
        payload.put("valor", p.aposta());
        payload.put("motivo", motivo);
        payload.put("lances", lances);
        outbox.publicar("PartidaFinalizada", p.id().toString(), payload);
        liberarServidor(p.id(), p.servidorId(), "PARTIDA_FINALIZADA");
        log.info("[corr={}] Partida finalizada ({}), vencedor: {}", p.id(), motivo, vencedor == null ? "empate" : vencedor);
    }

    private void liberarServidor(UUID partidaId, String servidorId, String motivo) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("partidaId", partidaId.toString());
        payload.put("servidorId", servidorId);
        payload.put("motivo", motivo);
        outbox.publicar("LiberacaoServidorSolicitada", partidaId.toString(), payload);
    }
}
