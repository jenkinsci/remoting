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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;

public class ConnectionHeadersTest {

    @Test
    public void emptyRoundTrip() throws Exception {
        assertThat(
                ConnectionHeaders.fromString(ConnectionHeaders.toString(Collections.emptyMap())),
                is(Collections.<String, String>emptyMap()));
    }

    @Test
    public void singleValueRoundTrip() throws Exception {
        assertThat(ConnectionHeaders.fromString(ConnectionHeaders.toString(Map.of("a", "b"))), is(Map.of("a", "b")));
    }

    @Test
    public void multiValueRoundTrip() throws Exception {
        Map<String, String> payload = new TreeMap<>();
        payload.put("a", "b");
        payload.put("c", null);
        payload.put("d", "\u0000\u0001\u0002\u0003\u1234SomeText\u0000MoreText\u0000");
        payload.put("e", "\"hi there\"");
        payload.put("e\u0000", "'hi there'");
        payload.put("e\u0009", "'hi\u0008there'");
        payload.put("\f\b\n\r\t/\\", "null");
        payload.put("a/b/c/d", "e\\f\\g\\h");
        assertThat(ConnectionHeaders.fromString(ConnectionHeaders.toString(payload)), is(payload));
    }

    @Test
    public void newlineEscaping() {
        assertThat(ConnectionHeaders.toString(Map.of("a\nmultiline\nkey", "b")), not(containsString("\n")));
    }

    @Test
    public void paddedData_1() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        expected.put("key", "value");
        expected.put("foo", "bar");
        assertThat(
                ConnectionHeaders.fromString("\n{\n  \"key\"\t:\f\"value\"\n,\n\"foo\"   :   \"bar\"\n}\n\n"),
                is(expected));
    }

    @Test
    public void paddedData_2() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        expected.put("key", "value/other");
        expected.put("foo", "bar\\manchu");
        assertThat(
                ConnectionHeaders.fromString(
                        " \b\t\n\r\f{ \b\t\n\r\f\"key\" \b\t\n\r\f: \b\t\n\r\f\"value\\/other\" \b\t\n\r\f, \b\t\n\r\f\"foo\" "
                                + "\b\t\n\r\f: \b\t\n\r\f\"bar\\\\manchu\" \b\t\n\r\f} \b\t\n\r\f"),
                is(expected));
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_0() throws Exception {
        ConnectionHeaders.fromString("   foobar   ");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_1() throws Exception {
        ConnectionHeaders.fromString("{foo:bar}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_2() throws Exception {
        ConnectionHeaders.fromString("    []   ");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_3() throws Exception {
        ConnectionHeaders.fromString("{}{}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_4() throws Exception {
        ConnectionHeaders.fromString("{null:null}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_5() throws Exception {
        ConnectionHeaders.fromString("{'foo':'bar'}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_6() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":{}}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_7() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":[]}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_8() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":null,}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_9() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":\"\\u\"}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_10() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":\"\\q\"}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_11() throws Exception {
        ConnectionHeaders.fromString("{\"foo\\u\":\"2\"}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_12() throws Exception {
        ConnectionHeaders.fromString("{\"foo\\w\":\"ho\"}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_13() throws Exception {
        ConnectionHeaders.fromString("{\"foo\"=\"bar\"}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_14() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":\"bar\"}//comment");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_15() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":nULL}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_16() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":nuLL}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_17() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":nulL}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_18() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":nu}");
    }

    @Test(expected = ConnectionHeaders.ParseException.class)
    public void malformedData_19() throws Exception {
        ConnectionHeaders.fromString("{\"foo\":\"bar\";\"foobar\":null}");
    }

    @Test(expected = IllegalAccessException.class)
    public void utilityClass_1() throws Exception {
        ConnectionHeaders.class.getDeclaredConstructor().newInstance();
    }

    @Test
    public void utilityClass_2() throws Exception {
        Constructor<ConnectionHeaders> constructor = ConnectionHeaders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final InvocationTargetException e = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertThat(e.getCause(), instanceOf(IllegalAccessError.class));
    }
}
