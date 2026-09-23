package com.eottabom.migration.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 버전 비교. 숫자 부분을 먼저 비교하고, 같으면 수식어를 Maven 순서로 비교한다.
 * alpha &lt; beta &lt; milestone(M) &lt; rc/cr &lt; snapshot &lt; 정식(없음, Final, RELEASE, GA, jre 등) &lt; sp
 * 예) 3.0.0-RC1 &lt; 3.0.0 = 3.0.0.Final = 3.0.0.RELEASE, 7.0.0.Beta2 &lt; 7.0.0.CR1 &lt; 7.0.0
 */
public final class Versions {

    private static final Pattern NUMERIC_PREFIX = Pattern.compile("^(\\d+(?:\\.\\d+)*)(.*)$");
    private static final Pattern QUALIFIER = Pattern.compile("^([a-z]+)(\\d*)");

    private Versions() {
    }

    public static int compare(String a, String b) {
        Parsed x = parse(a);
        Parsed y = parse(b);
        for (int i = 0; i < Math.max(x.numbers.size(), y.numbers.size()); i++) {
            int d = Integer.compare(i < x.numbers.size() ? x.numbers.get(i) : 0, i < y.numbers.size() ? y.numbers.get(i) : 0);
            if (d != 0) {
                return d;
            }
        }
        int d = Integer.compare(x.rank, y.rank);
        return d != 0 ? d : Integer.compare(x.qualifierNumber, y.qualifierNumber);
    }

    public static int major(String version) {
        return parse(version).numbers.get(0);
    }

    private record Parsed(List<Integer> numbers, int rank, int qualifierNumber) {
    }

    private static Parsed parse(String version) {
        String v = version == null ? "0" : version.trim();
        Matcher m = NUMERIC_PREFIX.matcher(v);
        List<Integer> numbers = new ArrayList<>();
        String rest = "";
        if (m.matches()) {
            for (String token : m.group(1).split("\\.")) {
                numbers.add(Integer.parseInt(token));
            }
            rest = m.group(2);
        } else {
            numbers.add(0);
            rest = v;
        }
        String qualifier = rest.replaceFirst("^[.\\-_]", "").toLowerCase(Locale.ROOT);
        Matcher q = QUALIFIER.matcher(qualifier);
        if (qualifier.isEmpty() || !q.find()) {
            return new Parsed(numbers, 5, 0);
        }
        if (q.group(1).equals("snapshot")) {
            // rc 보다 뒤, 정식보다 앞
            return new Parsed(numbers, 4, Integer.MAX_VALUE);
        }
        int number = q.group(2).isEmpty() ? 0 : Integer.parseInt(q.group(2));
        int rank = switch (q.group(1)) {
            case "alpha", "a" -> 1;
            case "beta", "b" -> 2;
            case "milestone", "m" -> 3;
            case "rc", "cr" -> 4;
            case "sp" -> 6;
            // final, release, ga, jre, android 등 정식 릴리즈를 뜻하거나 변형을 뜻하는 수식어
            default -> 5;
        };
        return new Parsed(numbers, rank, number);
    }
}
