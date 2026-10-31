package com.finkraft.canonical;

/** One error from an ASP or from Finkraft's own validator; any field may be null. */
public record AspError(String code, String field, String rule, String message) {}
