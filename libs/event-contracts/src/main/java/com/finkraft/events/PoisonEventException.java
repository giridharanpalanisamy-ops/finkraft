package com.finkraft.events;

/**
 * A record that can never succeed, however often it is retried (illegal transition, unmapped
 * status, undecodable payload). Consumer error handlers send it straight to the dead-letter topic.
 */
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
