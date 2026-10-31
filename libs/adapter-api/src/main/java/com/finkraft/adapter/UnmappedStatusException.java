package com.finkraft.adapter;

/** The ASP sent a status this adapter has no mapping for. Never guessed: the record is dead-lettered. */
public class UnmappedStatusException extends RuntimeException {

    public UnmappedStatusException(String message) {
        super(message);
    }

    public UnmappedStatusException(String message, Throwable cause) {
        super(message, cause);
    }
}
