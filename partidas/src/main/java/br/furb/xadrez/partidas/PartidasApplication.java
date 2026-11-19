package br.furb.xadrez.partidas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class PartidasApplication {
    public static void main(String[] args) {
        SpringApplication.run(PartidasApplication.class, args);
    }
}
