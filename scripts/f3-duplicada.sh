#!/usr/bin/env bash
# F3 - Mensagem duplicada: publica a MESMA mensagem (mesmo messageId) 5 vezes.
# Esperado: um unico efeito (premio creditado uma vez, Elo atualizado uma vez).
source "$(dirname "$0")/lib.sh"
S=$(date +%s); A="f3a$S"; B="f3b$S"
MID=$(uuid); PID=$(uuid)

titulo "F3 - mesma PartidaFinalizada publicada 5 vezes (messageId $MID)"
passo "saldo antes"; saldo "$A"
MSG="{\"messageId\":\"$MID\",\"correlationId\":\"$PID\",\"type\":\"PartidaFinalizada\",\"version\":1,\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"payload\":{\"partidaId\":\"$PID\",\"brancas\":\"$A\",\"pretas\":\"$B\",\"jogadores\":[\"$A\",\"$B\"],\"vencedor\":\"$A\",\"perdedor\":\"$B\",\"empate\":false,\"valor\":50,\"motivo\":\"XEQUE_MATE\",\"lances\":[]}}"
ARN=$(topic_arn PartidaFinalizada)
for i in 1 2 3 4 5; do
  aws_cli sns publish --topic-arn "$ARN" --message "$MSG" --query MessageId --output text >/dev/null
  echo "   publicada $i/5"
done
sleep 5
passo "saldo depois: +100 uma unica vez"; saldo "$A"
sql_carteira "SELECT jogador_id, tipo, valor FROM lancamentos WHERE partida_id = '$PID'"
sql_carteira "SELECT count(*) AS vezes_processada FROM processed_messages WHERE message_id = '$MID'"
sql_ranking  "SELECT jogador_id, rating, partidas FROM ratings WHERE jogador_id IN ('$A','$B')"
passo "logs de duplicata ignorada"
docker compose logs --no-log-prefix carteira ranking 2>/dev/null | grep "duplicada ignorada" | grep "$PID" || true
