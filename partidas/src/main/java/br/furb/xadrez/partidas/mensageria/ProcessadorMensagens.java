package br.furb.xadrez.partidas.mensageria;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import br.furb.xadrez.partidas.dominio.SagaService;

/**
 * R1: o messageId e registrado em processed_messages NA MESMA TRANSACAO do efeito.
 * Se a mensagem chegar de novo, o INSERT nao insere nada e o efeito nao se repete.
 */
@Component
public class ProcessadorMensagens {

    private static final Logger log = LoggerFactory.getLogger(ProcessadorMensagens.class);

    private final JdbcTemplate jdbc;
    private final SagaService saga;

    public ProcessadorMensagens(JdbcTemplate jdbc, SagaService saga) {
        this.jdbc = jdbc;
        this.saga = saga;
    }

    /** @return false se a mensagem ja tinha sido processada. */
    @Transactional
    public boolean processar(Envelope env) {
        int inseridas = jdbc.update(
                "INSERT INTO processed_messages (message_id, type) VALUES (?, ?) ON CONFLICT (message_id) DO NOTHING",
                env.messageId(), env.type());
        if (inseridas == 0) {
            return false;
        }
        log.info("[corr={}] Recebido {} v{}", env.correlationId(), env.type(), env.version());
        switch (env.type()) {
            case "ApostasDebitadas" -> saga.aoDebitarApostas(env);
            case "ApostasRecusadas" -> saga.aoRecusarApostas(env);
            case "ServidorAlocado" -> saga.aoAlocarServidor(env);
            case "AlocacaoFalhou" -> saga.aoFalharAlocacao(env);
            default -> log.info("Tipo {} nao interessa a este servico; ignorado", env.type());
        }
        return true;
    }
}
