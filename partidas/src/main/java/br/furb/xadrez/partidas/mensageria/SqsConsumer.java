package br.furb.xadrez.partidas.mensageria;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;

/**
 * Loop de consumo da fila do servico.
 * R6: qualquer erro de rede/broker e capturado, espera com backoff e tenta de novo,
 * sem reiniciar o processo. A mensagem so e apagada depois que o efeito foi commitado (R1).
 */
@Component
public class SqsConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsConsumer.class);

    private final SqsClient sqs;
    private final ObjectMapper mapper;
    private final ProcessadorMensagens processador;
    private final String nomeFila;
    private volatile boolean rodando = true;
    private Thread thread;

    public SqsConsumer(SqsClient sqs, ObjectMapper mapper, ProcessadorMensagens processador,
                       @Value("${xadrez.mensageria.fila}") String nomeFila) {
        this.sqs = sqs;
        this.mapper = mapper;
        this.processador = processador;
        this.nomeFila = nomeFila;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void iniciar() {
        thread = new Thread(this::loop, "consumidor-" + nomeFila);
        thread.setDaemon(true);
        thread.start();
    }

    @PreDestroy
    public void parar() {
        rodando = false;
    }

    private void loop() {
        String url = null;
        long espera = 1000;
        boolean avisado = false;
        while (rodando) {
            try {
                if (url == null) {
                    final String fila = nomeFila;
                    url = sqs.getQueueUrl(r -> r.queueName(fila)).queueUrl();
                    log.info("Consumindo a fila {} ({})", nomeFila, url);
                }
                final String u = url;
                List<Message> msgs = sqs.receiveMessage(r -> r.queueUrl(u).maxNumberOfMessages(10).waitTimeSeconds(10)
                        .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)).messages();
                if (avisado) {
                    log.info("Reconectado ao broker, voltando a consumir {}", nomeFila);
                    avisado = false;
                }
                espera = 1000;
                for (Message m : msgs) {
                    tratar(u, m);
                }
            } catch (Exception e) {
                if (!avisado) {
                    log.warn("Broker indisponivel ({}). Tentando reconectar...", e.getMessage());
                    avisado = true;
                }
                dormir(espera);
                espera = Math.min(espera * 2, 10_000);
            }
        }
    }

    private void tratar(String url, Message m) {
        String tentativa = m.attributes().getOrDefault(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, "?");
        try {
            Envelope env = Envelope.de(desembrulhar(m.body()));
            boolean novo = processador.processar(env);
            if (!novo) {
                log.info("[corr={}] Mensagem duplicada ignorada: {} {}", env.correlationId(), env.type(), env.messageId());
            }
            sqs.deleteMessage(r -> r.queueUrl(url).receiptHandle(m.receiptHandle()));
        } catch (MensagemInvalidaException e) {
            log.error("Mensagem invalida (tentativa {}): {}. Sera reentregue e, apos o limite, vai para a DLQ.",
                    tentativa, e.getMessage());
        } catch (Exception e) {
            log.error("Falha ao processar mensagem (tentativa {}): {}. Sera reentregue.", tentativa, e.toString());
        }
    }

    /** Aceita tanto entrega "raw" quanto a notificacao SNS embrulhada. */
    private JsonNode desembrulhar(String body) {
        try {
            JsonNode n = mapper.readTree(body);
            if (n != null && "Notification".equals(n.path("Type").asText()) && n.hasNonNull("Message")) {
                n = mapper.readTree(n.get("Message").asText());
            }
            return n;
        } catch (Exception e) {
            throw new MensagemInvalidaException("corpo nao e JSON valido");
        }
    }

    private static void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
