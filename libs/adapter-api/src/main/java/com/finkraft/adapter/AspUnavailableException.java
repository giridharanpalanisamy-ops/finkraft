package com.finkraft.adapter;

/** The ASP could not be reached or answered with an error. */
public class AspUnavailableException extends RuntimeException {

    public AspUnavailableException(String message) {
        super(message);
    }

    public AspUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
