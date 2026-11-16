package com.compact.crm.dto.response;

import com.compact.crm.enums.BackupKind;
import com.compact.crm.enums.BackupStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

// What an admin sees about one backup or readable export. Deliberately carries
// no connection details, storage URL or raw tool output - only status, timing,
// size, the SHA-256 to check a download against, and a scrubbed failure reason.
@Getter
@Builder
public class BackupResponse {

    private Long id;
    // DUMP = full restorable backup (.dump); READABLE_EXPORT = ZIP of CSV files (not restorable)
    private BackupKind kind;
    private BackupStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Long durationMs;
    private Long sizeBytes;
    private String sha256;
    private Integer tableCount;
    // readable exports only: rows written to the CSV files
    private Long totalRows;
    private String serverVersion;
    private String pgDumpVersion;
    private String initiatedByName;
    private String errorMessage;
    // true while the file can still be downloaded (SUCCEEDED, not removed or expired)
    private boolean available;
    private String fileName;
    // readable exports only: when the file is deleted at the latest
    private LocalDateTime expiresAt;
}
