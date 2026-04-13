package org.hastingtx.meshrelay;

import java.util.*;

/**
 * Minimal recursive-descent JSON parser — JDK only, zero dependencies.
 * Replaces fragile regex extraction throughout the codebase.
 *
 * Usage:
 *   Json obj = Json.parse("{\"name\":\"test\",\"count\":42}");
 *   String name = obj.getString("name");        // "test"
 *   int count   = obj.getInt("count");           // 42
 *
 *   Json arr = Json.parse("[{\"id\":1},{\"id\":2}]");
 *   for (Json item : arr.asList()) {
 *       int id = item.getInt("id");
 *   }
 *
 * Null-safe navigation:
 *   json.get("missing").getString("nested")  → null (no NPE)
 *   json.get("missing").asList()             → [] (no NPE)
 */
public class Json {

    private final Object value;

    private Json(Object value) {
        this.value = value;
    }

    /** Parse a JSON string into a Json wrapper. */
    public static Json parse(String json) {
        if (json == null || json.isBlank())
            throw new IllegalArgumentException("Cannot parse null or blank JSON");
        return new Json(new Parser(json).parseValue());
    }

    /**
     * Escape a string for safe embedding in a JSON string literal.
     * Replaces the duplicated jsonEscape() methods across the codebase.
     */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // ── Type checks ──────────────────────────────────────────────────────

    public boolean isObject() { return value instanceof Map; }
    public boolean isArray()  { return value instanceof List; }
    public boolean isString() { return value instanceof String; }
    public boolean isNumber() { return value instanceof Number; }
    public boolean isNull()   { return value == null; }

    // ── Object accessors ─────────────────────────────────────────────────

    /** Get a nested value by key. Returns Json(null) if this is not an object or key is absent. */
    public Json get(String key) {
        if (!(value instanceof Map)) return new Json(null);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) value;
        return new Json(map.get(key));
    }

    /** True if this is an object and contains the given key. */
    public boolean has(String key) {
        if (!(value instanceof Map)) return false;
        return ((Map<?, ?>) value).containsKey(key);
    }

    /** Get a string value by key. Numbers/booleans are converted via toString(). */
    public String getString(String key) {
        if (!(value instanceof Map)) return null;
        @SuppressWarnings("unchecked")
        Object v = ((Map<String, Object>) value).get(key);
        if (v instanceof String s) return s;
        return v != null ? v.toString() : null;
    }

    /** Get a string value by key, with a default if missing or null. */
    public String getString(String key, String defaultValue) {
        String v = getString(key);
        return v != null ? v : defaultValue;
    }

    /** Get an int value by key. Throws if key is missing or not numeric. */
    public int getInt(String key) {
        if (!(value instanceof Map)) throw new NoSuchElementException("Not an object");
        @SuppressWarnings("unchecked")
        Object v = ((Map<String, Object>) value).get(key);
        if (v instanceof Number n) return n.intValue();
        throw new NoSuchElementException("Missing or non-numeric key: " + key);
    }

    /** Get an int value by key, with a default if missing or not numeric. */
    public int getInt(String key, int defaultValue) {
        if (!(value instanceof Map)) return defaultValue;
        @SuppressWarnings("unchecked")
        Object v = ((Map<String, Object>) value).get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }

    /** Get a long value by key. Throws if key is missing or not numeric. */
    public long getLong(String key) {
        if (!(value instanceof Map)) throw new NoSuchElementException("Not an object");
        @SuppressWarnings("unchecked")
        Object v = ((Map<String, Object>) value).get(key);
        if (v instanceof Number n) return n.longValue();
        throw new NoSuchElementException("Missing or non-numeric key: " + key);
    }

    /** Get a long value by key, with a default if missing or not numeric. */
    public long getLong(String key, long defaultValue) {
        if (!(value instanceof Map)) return defaultValue;
        @SuppressWarnings("unchecked")
        Object v = ((Map<String, Object>) value).get(key);
        return v instanceof Number n ? n.longValue() : defaultValue;
    }

    // ── Array accessors ──────────────────────────────────────────────────

    /** Return this value as a list of Json wrappers. Returns empty list if not an array. */
    public List<Json> asList() {
        if (!(value instanceof List<?> list)) return List.of();
        List<Json> result = new ArrayList<>(list.size());
        for (Object item : list) result.add(new Json(item));
        return result;
    }

    /** Number of elements (array) or entries (object). Returns 0 for other types. */
    public int size() {
        if (value instanceof List<?> l) return l.size();
        if (value instanceof Map<?, ?> m) return m.size();
        return 0;
    }

    // ── Raw value access ─────────────────────────────────────────────────

    /** Return the underlying String value. */
    public String asString() {
        return value instanceof String s ? s : (value != null ? value.toString() : null);
    }

    /** Return the underlying raw value (Map, List, String, Number, Boolean, or null). */
    public Object raw() { return value; }

    @Override
    public String toString() {
        return String.valueOf(value);
    }

    // ══════════════════════════════════════════════════════════════════════
    // Recursive-descent parser
    // ══════════════════════════════════════════════════════════════════════

    private static class Parser {
        private final String input;
        private int pos;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        Object parseValue() {
            skipWhitespace();
            if (pos >= input.length()) throw error("Unexpected end of input");
            char c = input.charAt(pos);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't', 'f' -> parseBoolean();
                case 'n' -> parseNull();
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw error("Unexpected character: " + c);
                }
            };
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object val = parseValue();
                map.put(key, val);
                skipWhitespace();
                if (pos >= input.length()) throw error("Unterminated object");
                if (input.charAt(pos) == '}') { pos++; return map; }
                expect(',');
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') { pos++; return list; }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (pos >= input.length()) throw error("Unterminated array");
                if (input.charAt(pos) == ']') { pos++; return list; }
                expect(',');
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < input.length()) {
                char c = input.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= input.length()) throw error("Unterminated escape");
                    char esc = input.charAt(pos++);
                    switch (esc) {
                        case '"'  -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/'  -> sb.append('/');
                        case 'b'  -> sb.append('\b');
                        case 'f'  -> sb.append('\f');
                        case 'n'  -> sb.append('\n');
                        case 'r'  -> sb.append('\r');
                        case 't'  -> sb.append('\t');
                        case 'u'  -> {
                            if (pos + 4 > input.length()) throw error("Truncated \\u escape");
                            sb.append((char) Integer.parseInt(input.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("Invalid escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("Unterminated string");
        }

        Number parseNumber() {
            int start = pos;
            if (pos < input.length() && input.charAt(pos) == '-') pos++;
            while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            boolean isFloat = false;
            if (pos < input.length() && input.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            if (pos < input.length() && (input.charAt(pos) == 'e' || input.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < input.length() && (input.charAt(pos) == '+' || input.charAt(pos) == '-')) pos++;
                while (pos < input.length() && input.charAt(pos) >= '0' && input.charAt(pos) <= '9') pos++;
            }
            String num = input.substring(start, pos);
            if (isFloat) return Double.parseDouble(num);
            long l = Long.parseLong(num);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return (int) l;
            return l;
        }

        Boolean parseBoolean() {
            if (input.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
            if (input.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
            throw error("Expected boolean");
        }

        Object parseNull() {
            if (input.startsWith("null", pos)) { pos += 4; return null; }
            throw error("Expected null");
        }

        void expect(char c) {
            skipWhitespace();
            if (pos >= input.length() || input.charAt(pos) != c)
                throw error("Expected '" + c + "'");
            pos++;
        }

        char peek() {
            return pos < input.length() ? input.charAt(pos) : 0;
        }

        void skipWhitespace() {
            while (pos < input.length()) {
                char c = input.charAt(pos);
                if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break;
                pos++;
            }
        }

        IllegalArgumentException error(String msg) {
            int start = Math.max(0, pos - 20);
            int end   = Math.min(input.length(), pos + 20);
            return new IllegalArgumentException(
                msg + " at position " + pos + ": ..." + input.substring(start, end) + "...");
        }
    }
}
