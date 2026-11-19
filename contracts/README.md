# Contratos das mensagens

Toda mensagem usa o envelope de [`envelope.schema.json`](envelope.schema.json):

```json
{
  "messageId": "uuid unico (chave de idempotencia)",
  "correlationId": "id da partida (liga todas as mensagens da saga)",
  "type": "PartidaFinalizada",
  "version": 1,
  "occurredAt": "2026-11-26T19:00:00Z",
  "payload": { }
}
```

O `payload` de cada tipo e descrito em `events/<Tipo>.v<versao>.schema.json` (JSON Schema 2020-12).
Os servicos Node validam envelope e payload com esses arquivos; o servico Java valida os campos obrigatorios.
Mensagem invalida nao e apagada da fila: depois de 3 tentativas o SQS a move para a DLQ (F4).

Cada tipo tem um topico SNS proprio (`xadrez-<Tipo>`); cada servico tem uma fila SQS assinando os topicos que lhe interessam.

| Tipo | Produtor | Consumidores | Significado |
|---|---|---|---|
| `ApostasSolicitadas` | Partidas | Carteira | Par formado; pede o debito da aposta dos dois jogadores |
| `ApostasDebitadas` | Carteira | Partidas | Aposta debitada dos dois jogadores |
| `ApostasRecusadas` | Carteira | Partidas | Nao foi possivel debitar (ex.: saldo insuficiente); nada foi debitado |
| `AlocacaoSolicitada` | Partidas | Alocador (dependencia simulada) | Pede um servidor de jogo para a sala |
| `ServidorAlocado` | Alocador (dependencia simulada) | Partidas | Servidor de jogo pronto (pode chegar atrasado: F7) |
| `AlocacaoFalhou` | Alocador (dependencia simulada) | Partidas | Falha ao alocar servidor; dispara a compensacao (F1) |
| `LiberacaoServidorSolicitada` | Partidas | Alocador (dependencia simulada) | Devolve o servidor (fim de jogo ou resposta tardia) |
| `PartidaIniciada` | Partidas | Ranking/Notificacao | Partida comecou |
| `PartidaCancelada` | Partidas | Carteira, Ranking/Notificacao | Compensacao: partida cancelada; Carteira estorna se havia debito |
| `PartidaFinalizada` | Partidas | Carteira, Ranking/Notificacao | Fim de jogo: Carteira paga o premio, Ranking atualiza o Elo e salva o historico |
| `ApostasEstornadas` | Carteira | Ranking/Notificacao | Apostas devolvidas apos cancelamento |
| `PremioPago` | Carteira | Ranking/Notificacao | Premio creditado ao vencedor (ou aposta devolvida no empate) |
