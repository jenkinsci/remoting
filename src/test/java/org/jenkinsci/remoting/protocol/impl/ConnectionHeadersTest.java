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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class ConnectionHeadersTest {

    @Test
    void emptyRoundTrip() throws Exception {
        assertThat(
                ConnectionHeaders.fromString(ConnectionHeaders.toString(Collections.emptyMap())),
                is(Collections.<String, String>emptyMap()));
    }

    @Test
    void singleValueRoundTrip() throws Exception {
        assertThat(ConnectionHeaders.fromString(ConnectionHeaders.toString(Map.of("a", "b"))), is(Map.of("a", "b")));
    }

    @Test
    void multiValueRoundTrip() throws Exception {
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
    void newlineEscaping() {
        assertThat(ConnectionHeaders.toString(Map.of("a\nmultiline\nkey", "b")), not(containsString("\n")));
    }

    @Test
    void paddedData_1() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        expected.put("key", "value");
        expected.put("foo", "bar");
        assertThat(
                ConnectionHeaders.fromString("\n{\n  \"key\"\t:\f\"value\"\n,\n\"foo\"   :   \"bar\"\n}\n\n"),
                is(expected));
    }

    @Test
    void paddedData_2() throws Exception {
        Map<String, String> expected = new TreeMap<>();
        expected.put("key", "value/other");
        expected.put("foo", "bar\\manchu");
        assertThat(ConnectionHeaders.fromString("""
                                 \b\t
                                \r\f{ \b\t
                                \r\f"key" \b\t
                                \r\f: \b\t
                                \r\f"value\\/other" \b\t
                                \r\f, \b\t
                                \r\f"foo" \
                                \b\t
                                \r\f: \b\t
                                \r\f"bar\\\\manchu" \b\t
                                \r\f} \b\t
                                \r\f"""), is(expected));
    }

    @Test
    void malformedData_0() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("   foobar   "));
    }

    @Test
    void malformedData_1() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{foo:bar}"));
    }

    @Test
    void malformedData_2() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("    []   "));
    }

    @Test
    void malformedData_3() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{}{}"));
    }

    @Test
    void malformedData_4() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{null:null}"));
    }

    @Test
    void malformedData_5() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{'foo':'bar'}"));
    }

    @Test
    void malformedData_6() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":{}}"));
    }

    @Test
    void malformedData_7() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":[]}"));
    }

    @Test
    void malformedData_8() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":null,}"));
    }

    @Test
    void malformedData_9() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":\"\\u\"}"));
    }

    @Test
    void malformedData_10() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":\"\\q\"}"));
    }

    @Test
    void malformedData_11() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\\u\":\"2\"}"));
    }

    @Test
    void malformedData_12() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\\w\":\"ho\"}"));
    }

    @Test
    void malformedData_13() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\"=\"bar\"}"));
    }

    @Test
    void malformedData_14() {
        assertThrows(
                ConnectionHeaders.ParseException.class,
                () -> ConnectionHeaders.fromString("{\"foo\":\"bar\"}//comment"));
    }

    @Test
    void malformedData_15() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":nULL}"));
    }

    @Test
    void malformedData_16() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":nuLL}"));
    }

    @Test
    void malformedData_17() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":nulL}"));
    }

    @Test
    void malformedData_18() {
        assertThrows(ConnectionHeaders.ParseException.class, () -> ConnectionHeaders.fromString("{\"foo\":nu}"));
    }

    @Test
    void malformedData_19() {
        assertThrows(
                ConnectionHeaders.ParseException.class,
                () -> ConnectionHeaders.fromString("{\"foo\":\"bar\";\"foobar\":null}"));
    }

    @Test
    void utilityClass_1() {
        assertThrows(
                IllegalAccessException.class,
                () -> ConnectionHeaders.class.getDeclaredConstructor().newInstance());
    }

    @Test
    void utilityClass_2() throws Exception {
        Constructor<ConnectionHeaders> constructor = ConnectionHeaders.class.getDeclaredConstructor();
        constructor.setAccessible(true);

        final InvocationTargetException e = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertThat(e.getCause(), instanceOf(IllegalAccessError.class));
    }
}
