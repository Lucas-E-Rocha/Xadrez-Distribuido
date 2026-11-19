package br.furb.xadrez.partidas.mensageria;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.Topic;

/** Descobre o ARN do topico SNS de cada tipo de evento (topico = prefixo + type). */
@Component
public class TopicResolver {

    private final SnsClient sns;
    private final String prefixo;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public TopicResolver(SnsClient sns, @Value("${xadrez.mensageria.prefixo-topico}") String prefixo) {
        this.sns = sns;
        this.prefixo = prefixo;
    }

    public String arn(String type) {
        String arn = cache.get(type);
        if (arn != null) {
            return arn;
        }
        String sufixo = ":" + prefixo + type;
        for (Topic t : sns.listTopicsPaginator().topics()) {
            cache.putIfAbsent(nomeDe(t.topicArn()), t.topicArn());
            if (t.topicArn().endsWith(sufixo)) {
                arn = t.topicArn();
            }
        }
        if (arn == null) {
            throw new IllegalStateException("Topico " + prefixo + type + " nao existe (rodou o infra/init.sh?)");
        }
        cache.put(type, arn);
        return arn;
    }

    private String nomeDe(String arn) {
        String nome = arn.substring(arn.lastIndexOf(':') + 1);
        return nome.startsWith(prefixo) ? nome.substring(prefixo.length()) : nome;
    }
}
