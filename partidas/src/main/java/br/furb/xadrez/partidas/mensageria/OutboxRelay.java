package br.furb.xadrez.partidas.mensageria;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.MessageAttributeValue;

/**
 * Publica no SNS os eventos pendentes do outbox. Publica antes de marcar como enviado:
 * se cair no meio, o evento sai de novo (at-least-once) e os consumidores deduplicam pelo messageId.
 * FOR UPDATE SKIP LOCKED permite varias replicas do servico sem publicar o mesmo evento em paralelo.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SnsClient sns;
    private final TopicResolver topicos;
    private volatile boolean brokerFora = false;

    public OutboxRelay(JdbcTemplate jdbc, TransactionTemplate tx, SnsClient sns, TopicResolver topicos) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.sns = sns;
        this.topicos = topicos;
    }

    @Scheduled(fixedDelay = 500)
    public void drenar() {
        try {
            tx.executeWithoutResult(status -> {
                List<Map<String, Object>> pendentes = jdbc.queryForList(
                        "SELECT id, type, envelope FROM outbox WHERE enviado_em IS NULL ORDER BY id LIMIT 50 FOR UPDATE SKIP LOCKED");
                for (Map<String, Object> row : pendentes) {
                    String type = (String) row.get("type");
                    sns.publish(r -> r.topicArn(topicos.arn(type))
                            .message((String) row.get("envelope"))
                            .messageAttributes(Map.of("type",
                                    MessageAttributeValue.builder().dataType("String").stringValue(type).build())));
                    jdbc.update("UPDATE outbox SET enviado_em = now() WHERE id = ?", row.get("id"));
                    log.info("Evento publicado: {} (outbox id={})", type, row.get("id"));
                }
            });
            if (brokerFora) {
                brokerFora = false;
                log.info("Broker de volta: outbox voltou a drenar");
            }
        } catch (Exception e) {
            // A transacao faz rollback so dos que nao foram marcados; tenta de novo no proximo ciclo.
            if (!brokerFora) {
                log.warn("Nao consegui publicar eventos do outbox ({}). Eles ficam guardados ate o broker voltar.",
                        e.getMessage());
            }
            brokerFora = true;
        }
    }
}
