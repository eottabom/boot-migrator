package com.eottabom.migration.exec;

import java.util.List;
import java.util.Map;

/** 리포트 데이터용 작은 JSON 을 만든다. 값은 문자열 / 숫자 / 불리언 / 리스트 / 맵 / null. */
final class Json {

    private Json() {
    }

    /** 이미 JSON 인 문자열을 그대로 넣는다 (리포트 스크립트가 만든 NN-*.report.json). */
    record Raw(String json) {
    }

    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        append(out, value);
        return out.toString();
    }

    private static void append(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof Raw raw) {
            out.append(raw.json());
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) out.append(',');
                first = false;
                append(out, String.valueOf(e.getKey()));
                out.append(':');
                append(out, e.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) out.append(',');
                append(out, list.get(i));
            }
            out.append(']');
        } else {
            out.append('"');
            for (char c : value.toString().toCharArray()) {
                switch (c) {
                    case '"' -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default -> {
                        if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                        else out.append(c);
                    }
                }
            }
            out.append('"');
        }
    }
}
