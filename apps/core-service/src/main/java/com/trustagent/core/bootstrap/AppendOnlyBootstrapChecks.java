package com.trustagent.core.bootstrap;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.ToIntFunction;

public final class AppendOnlyBootstrapChecks {

    private AppendOnlyBootstrapChecks() {}

    public static void verifyExactCounts(
            Map<String, Integer> expected,
            ToIntFunction<String> actualCount,
            BiFunction<String, String, RuntimeException> failure) {
        Map<String, Integer> actual = expected.keySet().stream()
                .collect(java.util.stream.Collectors.toMap(table -> table, actualCount::applyAsInt));
        List<String> tablesWithExtraRows = expected.keySet().stream()
                .filter(table -> actual.get(table) > expected.get(table))
                .toList();
        if (!tablesWithExtraRows.isEmpty()) {
            throw failure.apply(
                    "RUNTIME_DATA_PRESENT",
                    "baseline에 없는 runtime 행이 있어 재적재할 수 없습니다: "
                            + String.join(", ", tablesWithExtraRows));
        }
        List<String> tablesWithMissingRows = expected.keySet().stream()
                .filter(table -> actual.get(table) < expected.get(table))
                .toList();
        if (!tablesWithMissingRows.isEmpty()) {
            throw failure.apply(
                    "BASELINE_CONTENT_MISMATCH",
                    "DB의 baseline 행 개수가 입력보다 적습니다: "
                            + String.join(", ", tablesWithMissingRows));
        }
    }

    public static String requireAllowedIdentifier(
            String identifier, Set<String> allowed, String description) {
        if (!allowed.contains(identifier)) {
            throw new IllegalArgumentException(
                    "허용되지 않은 " + description + " 식별자입니다: " + identifier);
        }
        return identifier;
    }

    public static void requireSourceIdentifier(
            String table, String idColumn, Map<String, String> sourceIdColumns, String description) {
        if (!idColumn.equals(sourceIdColumns.get(table))) {
            throw new IllegalArgumentException(
                    "허용되지 않은 " + description + " source 식별자입니다: "
                            + table + "." + idColumn);
        }
    }
}
