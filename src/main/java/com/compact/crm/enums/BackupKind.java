package com.compact.crm.enums;

// What one db_backup row produced (migration V20).
//  DUMP            - the full, restorable pg_dump custom-format backup (.dump).
//  READABLE_EXPORT - a ZIP of CSV files for viewing the data in Excel. It is
//                    NOT a restorable backup and has its own retention.
public enum BackupKind {
    DUMP,
    READABLE_EXPORT
}
