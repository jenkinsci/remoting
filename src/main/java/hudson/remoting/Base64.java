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

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

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
 * already on a byte array.
 *
 * @author Jeffrey Rodriguez
 * @author Sandy Gao
 * @version $Id: Base64.java,v 1.4 2007/07/19 04:38:32 ofung Exp $
 * @deprecated Use {@link java.util.Base64} instead
 */
@Deprecated
public final class Base64 {

    /**
     * Encodes hex octets into Base64
     *
     * @param binaryData Array containing binaryData
     * @return Encoded Base64 array. {@code null} if the input is null
     */
    @Nullable
    public static String encode(byte[] binaryData) {

        if (binaryData == null) {
            return null;
        }

        return java.util.Base64.getEncoder().encodeToString(binaryData);
    }

    /**
     * Decodes Base64 data into octets
     *
     * @param encoded string containing Base64 data
     * @return Array containing decoded data. {@code null} if the data cannot be decoded.
     */
    @CheckForNull
    @SuppressFBWarnings(
            value = "PZLA_PREFER_ZERO_LENGTH_ARRAYS",
            justification = "Null arrays are the part of the library API")
    public static byte[] decode(String encoded) {

        if (encoded == null) {
            return null;
        }

        return java.util.Base64.getDecoder().decode(encoded);
    }
}
