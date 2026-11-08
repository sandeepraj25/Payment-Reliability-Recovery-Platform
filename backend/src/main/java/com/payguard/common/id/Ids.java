package com.payguard.common.id;

import java.security.SecureRandom;

/**
 * Gateway-style identifiers such as {@code pay_4fQ9xZk2LmP8sT}.
 * 14 base62 characters = ~83 bits of randomness, so collisions are practically
 * impossible; the PRIMARY KEY constraint still backs this up.
 */
public final class Ids {

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int LENGTH = 14;

    private Ids() {
    }

    public static String newId(String prefix) {
        char[] out = new char[LENGTH];
        for (int i = 0; i < LENGTH; i++) {
            out[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        }
        return prefix + "_" + new String(out);
    }
}
