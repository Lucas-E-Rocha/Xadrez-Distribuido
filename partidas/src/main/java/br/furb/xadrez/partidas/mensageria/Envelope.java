package br.furb.xadrez.partidas.mensageria;

import com.fasterxml.jackson.databind.JsonNode;

/** Envelope padrao de todas as mensagens (contracts/envelope.schema.json). */
public record Envelope(String messageId, String correlationId, String type, int version, String occurredAt,
                       JsonNode payload) {

    public static Envelope de(JsonNode n) {
        for (String campo : new String[] { "messageId", "correlationId", "type", "version", "occurredAt", "payload" }) {
            if (n == null || !n.hasNonNull(campo)) {
                throw new MensagemInvalidaException("envelope sem o campo obrigatorio '" + campo + "'");
            }
        }
        if (!n.get("payload").isObject()) {
            throw new MensagemInvalidaException("payload precisa ser um objeto");
        }
        if (!n.get("version").canConvertToInt()) {
            throw new MensagemInvalidaException("version precisa ser inteiro");
        }
        return new Envelope(n.get("messageId").asText(), n.get("correlationId").asText(), n.get("type").asText(),
                n.get("version").asInt(), n.get("occurredAt").asText(), n.get("payload"));
    }

    /** Le um campo texto obrigatorio do payload. */
    public String texto(String campo) {
        JsonNode v = payload.get(campo);
        if (v == null || v.isNull() || v.asText().isBlank()) {
            throw new MensagemInvalidaException(type + ": payload sem o campo '" + campo + "'");
        }
        return v.asText();
    }
}
