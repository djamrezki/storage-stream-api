package ae.teletronics.storage.application.util;

import java.security.SecureRandom;

public final class TokenGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final SecureRandom RNG = new SecureRandom();
    private TokenGenerator() {}
    public static String randomToken(int len) {
        char[] c = new char[len];
        for (int i = 0; i < len; i++) c[i] = ALPHABET[RNG.nextInt(ALPHABET.length)];
        return new String(c);
    }
}
