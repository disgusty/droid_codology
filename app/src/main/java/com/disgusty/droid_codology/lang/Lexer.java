package com.disgusty.droid_codology.lang;

/**
 * Tokenizes one line at a time. The state carried between lines is an int:
 * bits 0-7 lexer mode, bits 8-15 mode detail, bits 16-30 bracket depth (for pair colorization).
 */
public interface Lexer {
    /** Tokenizes {@code s[0, len)} starting in {@code state}; returns the state at the end of the line. */
    int lex(char[] s, int len, int state, Tokens out);

    final class Tokens {
        /** Pairs of (start, style). */
        public int[] data = new int[64];
        public int size;

        public void clear() {
            size = 0;
        }

        public void add(int start, int style) {
            if (size > 0 && data[size - 1] == style) return;
            if (size > 0 && data[size - 2] == start) {
                // Zero-length previous token: overwrite it.
                data[size - 1] = style;
                if (size > 2 && data[size - 3] == style) size -= 2;
                return;
            }
            if (size + 2 > data.length) {
                int[] n = new int[data.length * 2];
                System.arraycopy(data, 0, n, 0, size);
                data = n;
            }
            data[size++] = start;
            data[size++] = style;
        }

        public int count() {
            return size / 2;
        }

        public int start(int i) {
            return data[i * 2];
        }

        public int style(int i) {
            return data[i * 2 + 1];
        }

        public int[] toArray() {
            int[] a = new int[size];
            System.arraycopy(data, 0, a, 0, size);
            return a;
        }
    }

    int MODE_MASK = 0xFF;

    /** Plain text: one token. */
    Lexer PLAIN = new Lexer() {
        @Override
        public int lex(char[] s, int len, int state, Tokens out) {
            out.add(0, Tok.PLAIN);
            return 0;
        }
    };
}
