package com.disgusty.droid_codology.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.util.Locale;

/**
 * Decoding and encoding of text files: detects the charset (BOM, strict UTF-8, locale fallback)
 * and the line-ending style; converts line breaks to '\n' on load and back on save.
 */
public final class TextFiles {
    private TextFiles() {
    }

    public static final String LF = "\n", CRLF = "\r\n", CR = "\r";

    public static final class Loaded {
        public char[] chars;
        public int length;
        public String charset;
        public boolean bom;
        public String eol;
        /** The file used more than one line-ending style (it is normalized on save). */
        public boolean mixedEol;
        /** Looks binary (NUL bytes); shown read-only. */
        public boolean binary;
    }

    /** Reads everything from {@code in}; {@code forcedCharset} null = auto-detect. */
    public static Loaded read(InputStream in, String forcedCharset) throws IOException {
        byte[] data = readAll(in);
        return decode(data, data.length, forcedCharset);
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
        byte[] b = new byte[64 * 1024];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }

    public static Loaded decode(byte[] data, int len, String forcedCharset) throws IOException {
        Loaded r = new Loaded();
        int skip = 0;
        String cs = forcedCharset;
        if (len >= 3 && (data[0] & 0xFF) == 0xEF && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF) {
            if (cs == null || cs.equalsIgnoreCase("UTF-8")) {
                cs = "UTF-8";
                skip = 3;
                r.bom = true;
            }
        } else if (len >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            if (cs == null) {
                cs = "UTF-16LE";
                skip = 2;
                r.bom = true;
            }
        } else if (len >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            if (cs == null) {
                cs = "UTF-16BE";
                skip = 2;
                r.bom = true;
            }
        }
        if (cs == null) {
            cs = utf16WithoutBom(data, len);
            if (cs == null) {
                int probe = Math.min(len, 8192);
                for (int i = 0; i < probe; i++) {
                    if (data[i] == 0) {
                        r.binary = true;
                        break;
                    }
                }
                if (isValidUtf8(data, len)) cs = "UTF-8";
                else {
                    cs = guessCyrillic(data, len);
                    if (cs == null) cs = fallbackCharset();
                }
            }
        }
        r.charset = cs;
        char[] chars = decodeChars(data, skip, len - skip, Charset.forName(cs));
        // Normalize line breaks in place and detect the dominant style.
        int crlf = 0, lf = 0, cr = 0, w = 0, n = chars.length;
        for (int i = 0; i < n; i++) {
            char c = chars[i];
            if (c == '\r') {
                if (i + 1 < n && chars[i + 1] == '\n') {
                    crlf++;
                    i++;
                } else cr++;
                chars[w++] = '\n';
            } else {
                if (c == '\n') lf++;
                chars[w++] = c;
            }
        }
        r.chars = chars;
        r.length = w;
        if (crlf >= lf && crlf >= cr && crlf > 0) r.eol = CRLF;
        else if (cr > lf) r.eol = CR;
        else if (lf == 0 && crlf == 0 && cr == 0) r.eol = defaultEol();
        else r.eol = LF;
        r.mixedEol = (crlf > 0 ? 1 : 0) + (lf > 0 ? 1 : 0) + (cr > 0 ? 1 : 0) > 1;
        return r;
    }

    public static String defaultEol() {
        return LF;
    }

    /** The legacy 8-bit charset most likely for this device's language. */
    public static String fallbackCharset() {
        String lang = Locale.getDefault().getLanguage();
        if (lang.equals("ru") || lang.equals("uk") || lang.equals("be") || lang.equals("bg") || lang.equals("sr") || lang.equals("mk")) {
            return "windows-1251";
        }
        if (lang.equals("el")) return "windows-1253";
        if (lang.equals("tr")) return "windows-1254";
        if (lang.equals("he") || lang.equals("iw")) return "windows-1255";
        if (lang.equals("ar") || lang.equals("fa")) return "windows-1256";
        if (lang.equals("pl") || lang.equals("cs") || lang.equals("sk") || lang.equals("hu") || lang.equals("ro")
                || lang.equals("sl") || lang.equals("hr")) {
            return "windows-1250";
        }
        if (lang.equals("ja")) return "Shift_JIS";
        if (lang.equals("zh")) return "GBK";
        if (lang.equals("ko")) return "EUC-KR";
        return "windows-1252";
    }

    /** "UTF-16LE"/"UTF-16BE" when every other byte is zero (ASCII-range text without a BOM). */
    static String utf16WithoutBom(byte[] b, int len) {
        int n = Math.min(len, 4096) & ~1;
        if (n < 8) return null;
        int zeroEven = 0, zeroOdd = 0;
        for (int i = 0; i < n; i += 2) {
            if (b[i] == 0) zeroEven++;
            if (b[i + 1] == 0) zeroOdd++;
        }
        int pairs = n / 2;
        if (zeroOdd > pairs * 0.7 && zeroEven < pairs * 0.1) return "UTF-16LE";
        if (zeroEven > pairs * 0.7 && zeroOdd < pairs * 0.1) return "UTF-16BE";
        return null;
    }

    /**
     * Russian/Ukrainian/... text in a legacy 8-bit encoding: Cyrillic words are runs of high bytes,
     * while Western accents are isolated. windows-1251 puts lowercase letters at 0xE0-0xFF, KOI8-R at
     * 0xC0-0xDF, and most text is lowercase. Returns null if the text doesn't look Cyrillic.
     */
    static String guessCyrillic(byte[] b, int len) {
        int n = Math.min(len, 64 * 1024);
        int high = 0, runs = 0, run = 0, lowCase1251 = 0, lowCaseKoi = 0, letters = 0;
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c >= 0xC0) {
                high++;
                if (++run == 3) runs++;
                if (c >= 0xE0) lowCase1251++;
                else lowCaseKoi++;
            } else {
                run = 0;
                if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) letters++;
            }
        }
        if (runs < 2 || high < letters / 2) return null;
        return lowCase1251 >= lowCaseKoi ? "windows-1251" : "KOI8-R";
    }

    static boolean isValidUtf8(byte[] b, int len) {
        int i = 0;
        while (i < len) {
            int c = b[i] & 0xFF;
            if (c < 0x80) {
                i++;
                continue;
            }
            int n;
            if (c >= 0xC2 && c <= 0xDF) n = 1;
            else if (c >= 0xE0 && c <= 0xEF) n = 2;
            else if (c >= 0xF0 && c <= 0xF4) n = 3;
            else return false;
            // A sequence cut off by the end of the data (e.g. a partial read) is tolerated.
            if (i + n >= len) return true;
            for (int k = 1; k <= n; k++) if ((b[i + k] & 0xC0) != 0x80) return false;
            // Overlong forms, UTF-16 surrogates and code points past U+10FFFF aren't UTF-8.
            int c1 = b[i + 1] & 0xFF;
            if (c == 0xE0 && c1 < 0xA0 || c == 0xED && c1 >= 0xA0 || c == 0xF0 && c1 < 0x90 || c == 0xF4 && c1 >= 0x90) return false;
            i += n + 1;
        }
        return true;
    }

    private static char[] decodeChars(byte[] data, int off, int len, Charset cs) throws CharacterCodingException {
        CharsetDecoder dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer out = CharBuffer.allocate((int) (len * (double) dec.maxCharsPerByte()) + 16);
        ByteBuffer in = ByteBuffer.wrap(data, off, len);
        CoderResult r = dec.decode(in, out, true);
        if (r.isOverflow()) {
            // Shouldn't happen with maxCharsPerByte sizing; fall back to the simple path.
            return cs.decode(ByteBuffer.wrap(data, off, len)).toString().toCharArray();
        }
        dec.flush(out);
        char[] c = new char[out.position()];
        out.flip();
        out.get(c);
        return c;
    }

    /** Encodes {@code text[0, len)} with '\n' turned into {@code eol}, streaming to {@code os}. */
    public static void write(OutputStream os, char[] text, int len, String charset, boolean bom, String eol) throws IOException {
        Charset cs = Charset.forName(charset);
        if (bom) {
            String n = cs.name().toUpperCase(Locale.ROOT);
            if (n.equals("UTF-8")) os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            else if (n.equals("UTF-16LE")) os.write(new byte[]{(byte) 0xFF, (byte) 0xFE});
            else if (n.equals("UTF-16BE")) os.write(new byte[]{(byte) 0xFE, (byte) 0xFF});
        }
        CharsetEncoder enc = cs.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE);
        CharBuffer chunk = CharBuffer.allocate(32 * 1024 + 4);
        ByteBuffer out = ByteBuffer.allocate((int) (chunk.capacity() * enc.maxBytesPerChar()) + 16);
        boolean lf = eol.equals(LF);
        int i = 0;
        while (i < len) {
            chunk.clear();
            while (i < len && chunk.remaining() >= 4) {
                char c = text[i++];
                if (c == '\n' && !lf) chunk.put(eol);
                else {
                    chunk.put(c);
                    // Never split a surrogate pair between chunks.
                    if (Character.isHighSurrogate(c) && i < len) chunk.put(text[i++]);
                }
            }
            chunk.flip();
            out.clear();
            enc.encode(chunk, out, i >= len);
            if (i >= len) enc.flush(out);
            os.write(out.array(), 0, out.position());
        }
        if (len == 0) {
            out.clear();
            enc.encode(CharBuffer.allocate(0), out, true);
            enc.flush(out);
            os.write(out.array(), 0, out.position());
        }
    }

    /** Whether every char of the text can be represented in the charset. */
    public static boolean canEncode(char[] text, int len, String charset) {
        CharsetEncoder enc = Charset.forName(charset).newEncoder();
        try {
            return enc.canEncode(CharBuffer.wrap(text, 0, len));
        } catch (RuntimeException e) {
            return false;
        }
    }
}
