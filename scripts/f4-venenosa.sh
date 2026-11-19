#!/usr/bin/env bash
# F4 - Mensagem venenosa: payload invalido.
# Esperado: N tentativas (3), depois DLQ; as outras mensagens continuam sendo processadas.
source "$(dirname "$0")/lib.sh"
S=$(date +%s)

titulo "F4 - publicando PartidaFinalizada com payload invalido"
ARN=$(topic_arn PartidaFinalizada)
MID=$(uuid)
aws_cli sns publish --topic-arn "$ARN" --output text --query MessageId \
  --message "{\"messageId\":\"$MID\",\"correlationId\":\"veneno\",\"type\":\"PartidaFinalizada\",\"version\":1,\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"payload\":{\"lixo\":true}}"

passo "enquanto isso, uma partida normal continua funcionando"
TA=$(entrar_fila "f4a$S" 10); TB=$(entrar_fila "f4b$S" 10)
esperar_status_ticket "$TA" 30

passo "esperando as 3 tentativas (visibility timeout 15s cada)..."
sleep 50
passo "logs das tentativas"
docker compose logs --no-log-prefix carteira ranking 2>/dev/null | grep "mensagem invalida" | tail -6
for q in carteira-eventos-dlq ranking-eventos-dlq; do
  passo "mensagens na $q"
  aws_cli sqs get-queue-attributes --queue-url "$(queue_url $q)" --attribute-names ApproximateNumberOfMessages \
    --query Attributes.ApproximateNumberOfMessages --output text
done
desistir_em_andamento "f4"
