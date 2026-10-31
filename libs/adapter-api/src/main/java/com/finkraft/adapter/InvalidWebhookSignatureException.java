package com.finkraft.adapter;

/** The webhook signature is missing or does not match. */
public class InvalidWebhookSignatureException extends RuntimeException {

    public InvalidWebhookSignatureException(String message) {
        super(message);
    }

    public InvalidWebhookSignatureException(String message, Throwable cause) {
        super(message, cause);
    }
}
