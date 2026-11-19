package br.furb.xadrez.partidas.config;

import java.net.URI;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.ssm.SsmClient;

/**
 * Clientes AWS. Endpoint, regiao e credenciais vem de variaveis de ambiente:
 * com AWS_ENDPOINT_URL=http://ministack:4566 o mesmo codigo roda no MiniStack;
 * sem ela, roda na AWS de verdade.
 */
@Configuration
public class AwsConfig {

    @Value("${xadrez.aws.endpoint}")
    private String endpoint;
    @Value("${xadrez.aws.region}")
    private String region;
    @Value("${xadrez.aws.access-key}")
    private String accessKey;
    @Value("${xadrez.aws.secret-key}")
    private String secretKey;

    private ClientOverrideConfiguration overrides() {
        return ClientOverrideConfiguration.builder()
                .apiCallTimeout(Duration.ofSeconds(25))
                .apiCallAttemptTimeout(Duration.ofSeconds(22))
                .build();
    }

    private <B extends software.amazon.awssdk.awscore.client.builder.AwsClientBuilder<B, ?>> B configure(B builder) {
        builder.region(Region.of(region))
               .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder;
    }

    @Bean
    public SqsClient sqsClient() {
        return configure(SqsClient.builder()).overrideConfiguration(overrides()).build();
    }

    @Bean
    public SnsClient snsClient() {
        return configure(SnsClient.builder()).overrideConfiguration(overrides()).build();
    }

    @Bean
    public SsmClient ssmClient() {
        return configure(SsmClient.builder()).overrideConfiguration(overrides()).build();
    }
}
