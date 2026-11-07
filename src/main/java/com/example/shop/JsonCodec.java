package com.example.shop;

import com.example.proxy.util.Json;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Small bounded recursive-descent JSON codec. No regex extraction or external compile dependencies.
 * Rejects duplicate keys, malformed UTF-8, lone surrogates, excessive nesting and trailing data. */
public final class JsonCodec {
    private final String source;
    private int pos;
    private JsonCodec(String source) { this.source = source; }
    public static Object parse(byte[] bytes) {
        return parseBounded(bytes,16384);
    }
    /** Trusted bundled catalog only; HTTP controllers retain the 16 KiB parse limit. */
    public static Object parseResource(byte[] bytes) { return parseBounded(bytes,131072); }
    private static Object parseBounded(byte[] bytes,int limit) {
        if (bytes.length > limit) throw new ShopException(413, "BODY_LIMIT", "JSON exceeds configured bound");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            var parser = new JsonCodec(text);
            Object result = parser.value(0); parser.space();
            if (parser.pos != text.length()) throw bad();
            return result;
        } catch (CharacterCodingException | NumberFormatException e) { throw bad(); }
    }
    private static ShopException bad() { return new ShopException(400, "INVALID_JSON", "Malformed JSON"); }
    private void space() { while (pos < source.length() && " \t\r\n".indexOf(source.charAt(pos)) >= 0) pos++; }
    private boolean eat(char c) { space(); if (pos < source.length() && source.charAt(pos) == c) { pos++; return true; } return false; }
    private void expect(char c) { if (!eat(c)) throw bad(); }
    private Object value(int depth) {
        if (depth > 32) throw bad();
        space(); if (pos == source.length()) throw bad();
        char c = source.charAt(pos);
        if (c == '"') return string();
        if (eat('{')) {
            Map<String,Object> map = new LinkedHashMap<>();
            if (eat('}')) return map;
            do {
                space(); String key = string(); expect(':');
                if (map.containsKey(key)) throw bad();
                map.put(key, value(depth + 1));
            } while (eat(','));
            expect('}'); return map;
        }
        if (eat('[')) {
            List<Object> list = new ArrayList<>();
            if (eat(']')) return list;
            do { list.add(value(depth + 1)); } while (eat(','));
            expect(']'); return list;
        }
        for (String literal : List.of("true", "false", "null")) {
            if (source.startsWith(literal, pos)) {
                pos += literal.length();
                return literal.equals("null") ? null : literal.equals("true");
            }
        }
        int start = pos;
        if (pos < source.length() && source.charAt(pos) == '-') pos++;
        if (pos == source.length()) throw bad();
        if (source.charAt(pos) == '0') pos++;
        else { if (!digit19(source.charAt(pos))) throw bad(); while (pos < source.length() && digit(source.charAt(pos))) pos++; }
        if (pos < source.length() && source.charAt(pos) == '.') {
            pos++; int fraction = pos;
            while (pos < source.length() && digit(source.charAt(pos))) pos++;
            if (fraction == pos) throw bad();
        }
        if (pos < source.length() && (source.charAt(pos) == 'e' || source.charAt(pos) == 'E')) {
            pos++; if (pos < source.length() && (source.charAt(pos) == '+' || source.charAt(pos) == '-')) pos++;
            int exponent = pos;
            while (pos < source.length() && digit(source.charAt(pos))) pos++;
            if (exponent == pos) throw bad();
        }
        return new BigDecimal(source.substring(start, pos));
    }
    private static boolean digit(char c) { return c >= '0' && c <= '9'; }
    private static boolean digit19(char c) { return c >= '1' && c <= '9'; }
    private String string() {
        if (pos >= source.length() || source.charAt(pos++) != '"') throw bad();
        StringBuilder out = new StringBuilder();
        while (pos < source.length()) {
            char c = source.charAt(pos++);
            if (c == '"') {
                for (int i=0; i<out.length(); i++) {
                    char s=out.charAt(i);
                    if (Character.isHighSurrogate(s)) { if (++i == out.length() || !Character.isLowSurrogate(out.charAt(i))) throw bad(); }
                    else if (Character.isLowSurrogate(s)) throw bad();
                }
                return out.toString();
            }
            if (c < 32) throw bad();
            if (c == '\\') {
                if (pos == source.length()) throw bad();
                c = source.charAt(pos++);
                c = switch (c) {
                    case '"', '\\', '/' -> c;
                    case 'b' -> '\b'; case 'f' -> '\f'; case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t';
                    case 'u' -> {
                        if (pos + 4 > source.length()) throw bad();
                        int code = 0;
                        for (int i=0; i<4; i++) {
                            char h=source.charAt(pos++);
                            int d=h>='0'&&h<='9'?h-'0':h>='a'&&h<='f'?h-'a'+10:h>='A'&&h<='F'?h-'A'+10:-1;
                            if(d<0)throw bad();code=code*16+d;
                        }
                        yield (char)code;
                    }
                    default -> throw bad();
                };
            }
            out.append(c);
        }
        throw bad();
    }
    public static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof String s) return Json.quote(s);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?,?> m) {
            var out = new StringJoiner(",", "{", "}");
            m.forEach((k,v) -> out.add(Json.quote(k.toString()) + ":" + write(v))); return out.toString();
        }
        if (value instanceof Collection<?> c) { var out = new StringJoiner(",", "[", "]"); c.forEach(v -> out.add(write(v))); return out.toString(); }
        throw new IllegalArgumentException("Unsupported JSON value");
    }
}
