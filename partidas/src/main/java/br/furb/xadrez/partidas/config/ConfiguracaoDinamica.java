package br.furb.xadrez.partidas.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Le configuracoes do SSM Parameter Store (categoria "Segredos e configuracoes").
 * Se o SSM estiver fora, usa o valor da variavel de ambiente.
 */
@Component
public class ConfiguracaoDinamica {

    private static final Logger log = LoggerFactory.getLogger(ConfiguracaoDinamica.class);

    private final SsmClient ssm;
    private final String parametroTimeout;
    private volatile int timeoutAlocacaoSegundos;

    public ConfiguracaoDinamica(SsmClient ssm,
                                @Value("${xadrez.ssm-parametro-timeout}") String parametroTimeout,
                                @Value("${xadrez.timeout-alocacao-segundos}") int timeoutPadrao) {
        this.ssm = ssm;
        this.parametroTimeout = parametroTimeout;
        this.timeoutAlocacaoSegundos = timeoutPadrao;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void carregar() {
        try {
            String valor = ssm.getParameter(r -> r.name(parametroTimeout)).parameter().value();
            timeoutAlocacaoSegundos = Integer.parseInt(valor.trim());
            log.info("Timeout de alocacao lido do SSM ({}): {}s", parametroTimeout, timeoutAlocacaoSegundos);
        } catch (Exception e) {
            log.warn("Nao foi possivel ler {} do SSM ({}); usando {}s", parametroTimeout, e.getMessage(),
                    timeoutAlocacaoSegundos);
        }
    }

    public int getTimeoutAlocacaoSegundos() {
        return timeoutAlocacaoSegundos;
    }
}
