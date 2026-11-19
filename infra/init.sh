#!/usr/bin/env bash
# Cria toda a infraestrutura no MiniStack (ou na AWS, se AWS_ENDPOINT_URL nao estiver definido).
# Idempotente: pode rodar quantas vezes quiser.  Uso:  docker compose run --rm init
set -euo pipefail

PREFIXO="${TOPIC_PREFIX:-xadrez-}"
MAX_RECEIVE="${MAX_RECEIVE_COUNT:-3}"        # F4: tentativas antes da DLQ
VISIBILIDADE="${VISIBILITY_TIMEOUT:-15}"     # segundos ate reentregar mensagem nao confirmada (F2)
BUCKET="${S3_BUCKET:-xadrez-historico}"

echo ">> Esperando o MiniStack em ${AWS_ENDPOINT_URL:-AWS}..."
for i in $(seq 1 60); do
  aws sqs list-queues >/dev/null 2>&1 && break
  sleep 1
done

# ---------- filas (uma por servico consumidor) + DLQ ----------
declare -A QUEUE_ARN
criar_fila() {
  local nome="$1"
  local dlq_url dlq_arn url
  dlq_url=$(aws sqs create-queue --queue-name "${nome}-dlq" --query QueueUrl --output text)
  dlq_arn=$(aws sqs get-queue-attributes --queue-url "$dlq_url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  url=$(aws sqs create-queue --queue-name "$nome" --query QueueUrl --output text)
  aws sqs set-queue-attributes --queue-url "$url" --attributes "{
    \"VisibilityTimeout\": \"${VISIBILIDADE}\",
    \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"${dlq_arn}\\\",\\\"maxReceiveCount\\\":\\\"${MAX_RECEIVE}\\\"}\"
  }"
  QUEUE_ARN[$nome]=$(aws sqs get-queue-attributes --queue-url "$url" --attribute-names QueueArn --query Attributes.QueueArn --output text)
  echo "   fila ${nome} (DLQ ${nome}-dlq, ${MAX_RECEIVE} tentativas)"
}

echo ">> Filas SQS"
for f in partidas-eventos carteira-eventos ranking-eventos alocador-solicitacoes; do criar_fila "$f"; done

# ---------- topicos (um por tipo de evento) e assinaturas ----------
# tipo -> filas que assinam
declare -A ASSINANTES=(
  [ApostasSolicitadas]="carteira-eventos"
  [ApostasDebitadas]="partidas-eventos"
  [ApostasRecusadas]="partidas-eventos"
  [AlocacaoSolicitada]="alocador-solicitacoes"
  [ServidorAlocado]="partidas-eventos"
  [AlocacaoFalhou]="partidas-eventos"
  [LiberacaoServidorSolicitada]="alocador-solicitacoes"
  [PartidaIniciada]="ranking-eventos"
  [PartidaCancelada]="carteira-eventos ranking-eventos"
  [PartidaFinalizada]="carteira-eventos ranking-eventos"
  [ApostasEstornadas]="ranking-eventos"
  [PremioPago]="ranking-eventos"
)

echo ">> Topicos SNS e assinaturas"
for tipo in "${!ASSINANTES[@]}"; do
  topic_arn=$(aws sns create-topic --name "${PREFIXO}${tipo}" --query TopicArn --output text)
  existentes=$(aws sns list-subscriptions-by-topic --topic-arn "$topic_arn" --query 'Subscriptions[].Endpoint' --output text || true)
  for fila in ${ASSINANTES[$tipo]}; do
    arn="${QUEUE_ARN[$fila]}"
    if [[ " $existentes " == *"$arn"* ]]; then
      echo "   ${tipo} -> ${fila} (ja existia)"
      continue
    fi
    aws sns subscribe --topic-arn "$topic_arn" --protocol sqs --notification-endpoint "$arn" \
      --attributes RawMessageDelivery=true >/dev/null
    echo "   ${tipo} -> ${fila}"
  done
done

# ---------- armazenamento de objetos ----------
echo ">> Bucket S3 ${BUCKET}"
aws s3api head-bucket --bucket "$BUCKET" >/dev/null 2>&1 || aws s3api create-bucket --bucket "$BUCKET" >/dev/null

# ---------- configuracoes ----------
echo ">> Parametros SSM"
aws ssm put-parameter --name /xadrez/partidas/timeout-alocacao-segundos --type String \
  --value "${TIMEOUT_ALOCACAO_SEGUNDOS:-10}" --overwrite >/dev/null
aws ssm put-parameter --name /xadrez/ranking/k-factor --type String --value "${ELO_K:-32}" --overwrite >/dev/null

echo ">> Infraestrutura pronta."
