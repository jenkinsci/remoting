/*
 * Copyright 1999-2002,2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package hudson.remoting;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import javax.annotation.CheckForNull;
import javax.annotation.Nullable;

/**
 * This class provides encode/decode for RFC 2045 Base64 as
 * defined by RFC 2045, N. Freed and N. Borenstein.
 * RFC 2045: Multipurpose Internet Mail Extensions (MIME)
 * Part One: Format of Internet Message Bodies. Reference
 * 1996 Available at: http://www.ietf.org/rfc/rfc2045.txt
 * This class is used by XML Schema binary format validation
 *
 * This implementation does not encode/decode streaming
 * data. You need the data that you will encode/decode
 * already on a byte arrray.
 *
 * @author Jeffrey Rodriguez
 * @author Sandy Gao
 * @version $Id: Base64.java,v 1.4 2007/07/19 04:38:32 ofung Exp $
 * @deprecated Use {@link java.util.Base64} instead
 */
@Deprecated
public final class  Base64 {

    static private final int  BASELENGTH         = 128;
    static private final int  LOOKUPLENGTH       = 64;
    static private final char PAD                = '=';
    static final private byte [] base64Alphabet        = new byte[BASELENGTH];
    static final private char [] lookUpBase64Alphabet  = new char[LOOKUPLENGTH];

    static {

        for (int i = 0; i < BASELENGTH; ++i) {
            base64Alphabet[i] = -1;
        }
        for (int i = 'Z'; i >= 'A'; i--) {
            base64Alphabet[i] = (byte) (i-'A');
        }
        for (int i = 'z'; i>= 'a'; i--) {
            base64Alphabet[i] = (byte) ( i-'a' + 26);
        }

        for (int i = '9'; i >= '0'; i--) {
            base64Alphabet[i] = (byte) (i-'0' + 52);
        }

        base64Alphabet['+']  = 62;
        base64Alphabet['/']  = 63;

        for (int i = 0; i<=25; i++)
            lookUpBase64Alphabet[i] = (char)('A'+i);

        for (int i = 26,  j = 0; i<=51; i++, j++)
            lookUpBase64Alphabet[i] = (char)('a'+ j);

        for (int i = 52,  j = 0; i<=61; i++, j++)
            lookUpBase64Alphabet[i] = (char)('0' + j);
        lookUpBase64Alphabet[62] = (char)'+';
        lookUpBase64Alphabet[63] = (char)'/';

    }

    protected static boolean isWhiteSpace(char octect) {
        return (octect == 0x20 || octect == 0xd || octect == 0xa || octect == 0x9);
    }

    protected static boolean isPad(char octect) {
        return (octect == PAD);
    }

    protected static boolean isData(char octect) {
        return (octect < BASELENGTH && base64Alphabet[octect] != -1);
    }

    protected static boolean isBase64(char octect) {
        return (isWhiteSpace(octect) || isPad(octect) || isData(octect));
    }

    /**
     * Encodes hex octects into Base64
     *
     * @param binaryData Array containing binaryData
     * @return Encoded Base64 array. {@code null} if the input is null
     */
    @Nullable
    public static String encode(byte[] binaryData) {

        if (binaryData == null)
            return null;

        return java.util.Base64.getEncoder().encodeToString(binaryData);
    }

    /**
     * Decodes Base64 data into octects
     *
     * @param encoded string containing Base64 data
     * @return Array containing decoded data. {@code null} if the data cannot be decoded.
     */
    @CheckForNull
    @SuppressFBWarnings(value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
            justification = "Null arrays are the part of the library API")
    public static byte[] decode(String encoded) {

        if (encoded == null)
            return null;

        return java.util.Base64.getDecoder().decode(encoded);
    }

    /**
     * remove WhiteSpace from MIME containing encoded Base64 data.
     *
     * @param data  the byte array of base64 data (with WS)
     * @return      the new length
     */
    protected static int removeWhiteSpace(char[] data) {
        if (data == null)
            return 0;

        // count characters that's not whitespace
        int newSize = 0;
        int len = data.length;
        for (int i = 0; i < len; i++) {
            if (!isWhiteSpace(data[i]))
                data[newSize++] = data[i];
        }
        return newSize;
    }
}
