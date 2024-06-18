/*
 * The MIT License
 *
 * Copyright (c) 2016, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkinsci.remoting.protocol.impl;

import edu.umd.cs.findbugs.annotations.NonNull;
import java.io.ObjectInputStream;
import java.util.Map;
import java.util.TreeMap;

/**
 * Utility class to handle the encoding and decoding of the connection headers. Connections headers are encoded
 * as a flat JSON object with exclusively {@link String} values. We use a pure text based format as this information
 * will be used to decide whether to accept a connection with the remote end, thus, until the connection has been
 * accepted we should not trust {@link ObjectInputStream} or an equivalent based mechanism for exchange of
 * pre-connection headers. We could use a header format equivalent to that of HTTP headers, the JSON format
 * of mapping two/from a {@link Map} with {@link String} keys and values has the advantages
 * <ul>
 * <li>
 * there is a 1:1 mapping between a {@code Map} and a JSON object
 * </li>
 * <li>
 * A JSON object cannot have {@code null} keys and neither can a {@link Map}
 * </li>
 * <li>
 * A JSON object can have {@code null} values and so can a {@link Map}
 * </li>
 * <li>
 * A JSON object can only have one value for any key whereas something like the HTTP Header format
 * allows for multiple values on any specific header
 * </li>
 * <li>
 * Tooling such as wireshark can detect JSON formatted data and present it nicely.
 * (We are assuming here that most usage of the headers will be after a TLS connection has been set-up
 * and thus anyone using wireshark to sniff the packets has access to both side's private keys
 * and as such is trusted to actually be peeking into the headers!)
 * </li>
 * </ul>
 *
 * @since 3.0
 */
public final class ConnectionHeaders {

    /**
     * Do not instantiate utility classes.
     */
    private ConnectionHeaders() {
        throw new IllegalAccessError("Utility class");
    }

    /**
     * Converts the headers into the String format.
     *
     * @param data the headers.
     * @return the string encoded header.
     */
    @NonNull
    public static String toString(@NonNull Map<String, String> data) {
        StringBuilder b = new StringBuilder();
        b.append('{');
        boolean first = true;
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (first) {
                first = false;
            } else {
                b.append(',');
            }
            b.append('"');
            appendEscaped(b, e.getKey());
            b.append("\":");
            if (e.getValue() == null) {
                b.append("null");
            } else {
                b.append('"');
                appendEscaped(b, e.getValue());
                b.append('"');
            }
        }
        b.append('}');
        return b.toString();
    }

    /**
     * Converts the headers from the String format.
     *
     * @param data the string encoded headers.
     * @return the headers.
     */
    @NonNull
    public static Map<String, String> fromString(@NonNull String data) throws ParseException {
        Map<String, String> result = new TreeMap<>();
        int state = 0;
        StringBuilder key = new StringBuilder();
        StringBuilder val = new StringBuilder();
        for (int i = 0, n = data.length(); i < n; i++) {
            char c = data.charAt(i);
            boolean isWhitespace = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f' || c == '\b';
            switch (state) {
                case 0:
                    if (isWhitespace) {
                        continue;
                    }
                    if (c != '{') {
                        throw new ParseException("Expecting '{' but got '" + c + "'");
                    }
                    state = 1;
                    break;
                case 1:
                    if (isWhitespace) {
                        continue;
                    }
                    if (c == '}') {
                        state = 9;
                    } else if (c != '\"') {
                        throw new ParseException("Expecting '\"' but got '" + c + "'");
                    } else {
                        state = 2;
                    }
                    break;
                case 2:
                    if (c == '\\') {
                        state = 3;
                    } else if (c == '\"') {
                        state = 4;
                    } else {
                        key.append(c);
                    }
                    break;
                case 3:
                    i = decodeEscape(key, c, data, i, n);
                    state = 2;
                    break;
                case 4:
                    if (isWhitespace) {
                        continue;
                    }
                    if (c != ':') {
                        throw new ParseException("Expecting ':' but got '" + c + "'");
                    }
                    state = 5;
                    break;
                case 5:
                    if (isWhitespace) {
                        continue;
                    }
                    if (c == 'n'
                            && i + 3 < n
                            && data.charAt(i + 1) == 'u'
                            && data.charAt(i + 2) == 'l'
                            && data.charAt(i + 3) == 'l') {
                        i += 3;
                        result.put(key.toString(), null);
                        key.setLength(0);
                        state = 8;
                        break;
                    }
                    if (c != '\"') {
                        throw new ParseException("Expecting '\"' or 'null' but got '" + c + "'");
                    }
                    state = 6;
                    break;
                case 6:
                    if (c == '\\') {
                        state = 7;
                    } else if (c == '\"') {
                        result.put(key.toString(), val.toString());
                        key.setLength(0);
                        val.setLength(0);
                        state = 8;
                    } else {
                        val.append(c);
                    }
                    break;
                case 7:
                    i = decodeEscape(val, c, data, i, n);
                    state = 6;
                    break;
                case 8:
                    if (isWhitespace) {
                        continue;
                    }
                    if (c == '}') {
                        state = 9;
                    } else if (c == ',') {
                        state = 10;
                    } else {
                        throw new ParseException("Expecting '}' or ',' but got '" + c + "'");
                    }
                    break;
                case 9:
                    if (!(isWhitespace)) {
                        throw new ParseException("Non-whitespace after '}'");
                    }
                    break;
                case 10:
                    if (isWhitespace) {
                        continue;
                    }
                    if (c != '\"') {
                        throw new ParseException("Expecting '\"' but got '" + c + "'");
                    } else {
                        state = 2;
                    }
                    break;
                default:
                    throw new ParseException("Unexpected parser state machine state: " + state);
            }
        }
        return result;
    }

    public static void appendEscaped(StringBuilder b, String str) {
        for (int i = 0, n = str.length(); i < n; i++) {
            char c = str.charAt(i);
            switch (c) {
                case '\b':
                    b.append("\\b");
                    break;
                case '\t':
                    b.append("\\t");
                    break;
                case '\f':
                    b.append("\\f");
                    break;
                case '\n':
                    b.append("\\n");
                    break;
                case '\r':
                    b.append("\\r");
                    break;
                case '"':
                case '\\':
                    b.append('\\').append(c);
                    break;
                default:
                    if (c <= 0x1f) {
                        // escape all control characters
                        b.append(encodeEscape(c));
                    } else {
                        b.append(c);
                    }
                    break;
            }
        }
    }

    private static int decodeEscape(StringBuilder val, char c, String str, int i, int n) throws ParseException {
        switch (c) {
            case '\"':
            case '\\':
            case '/':
                val.append(c);
                return i;
            case 'b':
                val.append('\b');
                return i;
            case 'f':
                val.append('\f');
                return i;
            case 'n':
                val.append('\n');
                return i;
            case 'r':
                val.append('\r');
                return i;
            case 't':
                val.append('\t');
                return i;
            case 'u':
                if (i + 4 < n) {
                    int n1 = Character.digit(str.charAt(i + 1), 16);
                    int n2 = Character.digit(str.charAt(i + 2), 16);
                    int n3 = Character.digit(str.charAt(i + 3), 16);
                    int n4 = Character.digit(str.charAt(i + 4), 16);
                    if (n1 == -1 || n2 == -1 || n3 == -1 || n4 == -1) {
                        throw new ParseException("Malformed unicode escape");
                    }
                    val.append((char) ((n1 << 12) | (n2 << 8) | (n3 << 4) | n4));
                    return i + 4;
                } else {
                    throw new ParseException("Malformed unicode escape");
                }
            default:
                throw new ParseException("Unknown character escape '" + c + "'");
        }
    }

    public static String encodeEscape(int c) {
        return "\\u" + Character.forDigit((c >> 12) & 15, 16)
                + Character.forDigit((c >> 8) & 15, 16)
                + Character.forDigit((c >> 4) & 15, 16)
                + Character.forDigit(c & 15, 16);
    }

    public static class ParseException extends Exception {
        public ParseException(String message) {
            super(message);
        }
    }
}
