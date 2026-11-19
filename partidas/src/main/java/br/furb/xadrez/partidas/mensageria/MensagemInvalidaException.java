package br.furb.xadrez.partidas.mensageria;

/** Mensagem que nunca vai dar certo (venenosa). Nao e apagada: apos N tentativas o SQS manda para a DLQ. */
public class MensagemInvalidaException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MensagemInvalidaException(String msg) {
        super(msg);
    }
}
