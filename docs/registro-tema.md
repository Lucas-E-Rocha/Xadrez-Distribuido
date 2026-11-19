# Registro do tema – Trabalho Final de Sistemas Distribuídos

**Tema:** Xadrez Distribuído – partidas multiplayer ranqueadas valendo moedas

**Integrantes:** Lucas Eduardo Rocha

**Serviços e linguagens:**

- Partidas (matchmaking, regras do jogo e orquestração da saga): Java (Spring Boot)
- Carteira (apostas, estornos e prêmios): Node.js
- Ranking/Histórico (Elo, notificações e histórico): Node.js
- Alocador de servidores de jogo (dependência instável simulada pelo grupo): Node.js

**Infraestrutura:** MiniStack (AWS local), com SNS/SQS (mensageria), S3 (histórico das partidas) e SSM Parameter Store (configurações). Um Postgres por serviço.

**Fluxo principal:** o jogador entra na fila e recebe 202 com um id. O serviço Partidas forma um par com a mesma aposta e reserva uma das N salas disponíveis, que são o recurso escasso. Em seguida pede à Carteira o débito da aposta dos dois jogadores. Com o débito confirmado, pede um servidor de jogo ao alocador, a **dependência lenta e instável**: ele demora e falha de forma aleatória, configurável por variável de ambiente. Se a alocação falhar ou passar do prazo, ocorre a **compensação**: a sala é liberada, a partida é cancelada e a Carteira estorna as apostas. Se o servidor ficar pronto depois do prazo, ele é liberado. Com o servidor alocado, os jogadores jogam. No fim, o evento `PartidaFinalizada` é publicado uma vez e consumido por **vários interessados**: a Carteira paga o prêmio ao vencedor e o Ranking atualiza o Elo e salva o histórico no S3. `PartidaCancelada` também é consumido pela Carteira (estorno) e pelo Ranking (notificação).

**Observação:** o motor de regras do xadrez vem de um projeto pessoal meu, já existente. Todo o sistema distribuído (serviços, mensageria, saga e infraestrutura) é novo.
