package com.compact.crm.service.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

// Writes the readable data export (a ZIP of CSV files, README.txt,
// manifest.json and relationships.csv) to a local file. The production
// implementation is JdbcReadableExporter. The result describes exactly what
// was written, so ReadableExportVerifier can check the finished file.
public interface ReadableExporter {

    Result export(Path zip, String rootFolder, long exportId) throws IOException, InterruptedException;

    record TableFile(String table, String entry, List<String> header, long rows, long guardedCells,
                     List<String> withheldColumns) {
    }

    record Result(String rootFolder, List<TableFile> tables, List<String> entries, Map<String, String> sha256ByEntry,
                  long relationshipCount, long totalRows, String databaseVersion, String crmSchemaVersion) {
    }
}
