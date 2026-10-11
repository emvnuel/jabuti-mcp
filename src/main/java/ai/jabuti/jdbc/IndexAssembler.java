package ai.jabuti.jdbc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns raw {@link java.sql.DatabaseMetaData} index rows into {@link IndexInfo}
 * values, including the primary-key detection fallback for engines whose PK
 * index name does not match the PK constraint name.
 */
final class IndexAssembler {

    private IndexAssembler() {
    }

    record IndexColumn(int position, String name) {
    }

    record PrimaryKey(Set<String> names, Set<String> columns) {
    }

    static List<IndexInfo> assemble(Map<String, List<IndexColumn>> columnsByIndex,
            Map<String, Boolean> uniqueByIndex, PrimaryKey primaryKey) {
        boolean anyPkNameMatchesIndex = columnsByIndex.keySet().stream().anyMatch(primaryKey.names()::contains);
        String claimedPrimary = null;
        List<IndexInfo> indexes = new ArrayList<>();
        for (Map.Entry<String, List<IndexColumn>> entry : columnsByIndex.entrySet()) {
            List<String> columns = sortedColumnNames(entry.getValue());
            boolean primary = primaryKey.names().contains(entry.getKey());
            if (!primary && claimedPrimary == null) {
                claimedPrimary = claimPrimaryByColumns(entry.getKey(), columns, uniqueByIndex.get(entry.getKey()),
                        primaryKey, anyPkNameMatchesIndex);
                primary = claimedPrimary != null;
            }
            indexes.add(new IndexInfo(entry.getKey(), columns, uniqueByIndex.get(entry.getKey()), primary));
        }
        return indexes;
    }

    /**
     * Some engines (e.g. H2) name the primary key index differently from the
     * PK constraint, so a column-set fallback is needed; it only applies when
     * no index name correlates with a reported PK name, otherwise a secondary
     * index on the PK columns would be misreported as primary.
     */
    private static String claimPrimaryByColumns(String indexName, List<String> columns, Boolean unique,
            PrimaryKey primaryKey, boolean anyPkNameMatchesIndex) {
        if (anyPkNameMatchesIndex || primaryKey.columns().isEmpty() || !Boolean.TRUE.equals(unique)) {
            return null;
        }
        return primaryKey.columns().equals(new LinkedHashSet<>(columns)) ? indexName : null;
    }

    private static List<String> sortedColumnNames(List<IndexColumn> columns) {
        return columns.stream()
                .sorted(Comparator.comparingInt(IndexColumn::position))
                .map(IndexColumn::name)
                .toList();
    }
}
