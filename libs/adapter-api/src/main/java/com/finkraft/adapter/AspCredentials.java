package com.finkraft.adapter;

/** Resolved credentials for one seller at one ASP. Never logged, never stored. */
public record AspCredentials(String apiKey) {

    @Override
    public String toString() {
        return "AspCredentials[***]";
    }
}
