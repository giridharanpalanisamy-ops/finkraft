package com.finkraft.validator.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;

final class Amounts {

    static final BigDecimal HUNDRED = new BigDecimal("100");

    private Amounts() {}

    /** Scale 2, HALF_UP, as every v1 arithmetic rule requires. */
    static BigDecimal round(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static boolean equal(BigDecimal expected, BigDecimal actual) {
        return round(expected).compareTo(round(actual)) == 0;
    }
}
