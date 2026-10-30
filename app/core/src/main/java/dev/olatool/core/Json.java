package dev.olatool.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A very small JSON reader, just enough for the two data files the app ships:
 * the CAN signal table and the fault table.
 *
 * <p>It exists so {@code :core} stays pure Java and the desktop harness can
 * parse the same assets the phone does. {@code org.json} is Android-only and
 * {@code java.util.Base64} is API 26, so both are avoided on purpose.
 *
 * <p>Values come back as {@link Map}, {@link List}, {@link String},
 * {@link Double}, {@link Boolean} or {@code null}.
 */
public final class Json {

    private final String text;
    private int at;

    private Json(String text) {
        this.text = text;
    }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.skipSpace();
        Object value = p.value();
        p.skipSpace();
        return value;
    }

    /** Convenience: parse and expect an object. */
    public static Map<String, Object> object(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return cast(o);
    }

    // ---------------------------------------------------------------- access

    @SuppressWarnings("unchecked")
    public static Map<String, Object> cast(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    public static Map<String, Object> map(Object parent, String key) {
        Object o = parent instanceof Map ? ((Map<?, ?>) parent).get(key) : null;
        return o instanceof Map ? cast(o) : new LinkedHashMap<>();
    }

    public static List<Object> array(Object parent, String key) {
        Object o = parent instanceof Map ? ((Map<?, ?>) parent).get(key) : null;
        return o instanceof List ? list(o) : new ArrayList<>();
    }

    public static String string(Object parent, String key, String fallback) {
        Object o = parent instanceof Map ? ((Map<?, ?>) parent).get(key) : null;
        return o instanceof String ? (String) o : fallback;
    }

    public static double number(Object parent, String key, double fallback) {
        Object o = parent instanceof Map ? ((Map<?, ?>) parent).get(key) : null;
        if (o instanceof Double) {
            return (Double) o;
        }
        if (o instanceof String) {
            try {
                return Double.parseDouble((String) o);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public static boolean flag(Object parent, String key) {
        Object o = parent instanceof Map ? ((Map<?, ?>) parent).get(key) : null;
        return o instanceof Boolean && (Boolean) o;
    }

    // --------------------------------------------------------------- parsing

    private Object value() {
        char c = peek();
        switch (c) {
            case '{':
                return objectValue();
            case '[':
                return arrayValue();
            case '"':
                return stringValue();
            case 't':
                expect("true");
                return Boolean.TRUE;
            case 'f':
                expect("false");
                return Boolean.FALSE;
            case 'n':
                expect("null");
                return null;
            default:
                return numberValue();
        }
    }

    private Map<String, Object> objectValue() {
        Map<String, Object> out = new LinkedHashMap<>();
        at++; // {
        skipSpace();
        if (peek() == '}') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            String key = stringValue();
            skipSpace();
            if (peek() != ':') {
                throw new IllegalArgumentException("expected ':' at " + at);
            }
            at++;
            skipSpace();
            out.put(key, value());
            skipSpace();
            char c = peek();
            at++;
            if (c == '}') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or '}' at " + at);
            }
        }
    }

    private List<Object> arrayValue() {
        List<Object> out = new ArrayList<>();
        at++; // [
        skipSpace();
        if (peek() == ']') {
            at++;
            return out;
        }
        while (true) {
            skipSpace();
            out.add(value());
            skipSpace();
            char c = peek();
            at++;
            if (c == ']') {
                return out;
            }
            if (c != ',') {
                throw new IllegalArgumentException("expected ',' or ']' at " + at);
            }
        }
    }

    private String stringValue() {
        if (peek() != '"') {
            throw new IllegalArgumentException("expected a string at " + at);
        }
        at++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            char c = text.charAt(at++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = text.charAt(at++);
            switch (e) {
                case '"':
                case '\\':
                case '/':
                    sb.append(e);
                    break;
                case 'b':
                    sb.append('\b');
                    break;
                case 'f':
                    sb.append('\f');
                    break;
                case 'n':
                    sb.append('\n');
                    break;
                case 'r':
                    sb.append('\r');
                    break;
                case 't':
                    sb.append('\t');
                    break;
                case 'u':
                    sb.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                    at += 4;
                    break;
                default:
                    sb.append(e);
            }
        }
    }

    private Double numberValue() {
        int start = at;
        while (at < text.length()) {
            char c = text.charAt(at);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                    || c == 'e' || c == 'E') {
                at++;
            } else {
                break;
            }
        }
        if (start == at) {
            throw new IllegalArgumentException("expected a value at " + at);
        }
        return Double.valueOf(text.substring(start, at));
    }

    private void expect(String word) {
        if (!text.startsWith(word, at)) {
            throw new IllegalArgumentException("expected " + word + " at " + at);
        }
        at += word.length();
    }

    private char peek() {
        if (at >= text.length()) {
            return '\0';
        }
        return text.charAt(at);
    }

    private void skipSpace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                at++;
            } else {
                return;
            }
        }
    }
}
