package com.compact.crm.repository;

import com.compact.crm.entity.DbBackup;
import com.compact.crm.enums.BackupKind;
import com.compact.crm.enums.BackupStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DbBackupRepository extends JpaRepository<DbBackup, Long> {

    // Any kind: only one backup job (dump or readable export) runs at a time.
    Optional<DbBackup> findFirstByStatusOrderByStartedAtDesc(BackupStatus status);

    List<DbBackup> findByStatus(BackupStatus status);

    // History, per kind, so neither kind can push the other off the page.
    List<DbBackup> findTop20ByKindOrderByStartedAtDescIdDesc(BackupKind kind);

    // Retention candidates of ONE kind: every successful row whose file is
    // still stored. Always filtered by kind - readable exports must never count
    // toward, or cause the deletion of, .dump backups (and vice versa).
    List<DbBackup> findByKindAndStatusAndRemovedAtIsNullOrderByStartedAtDescIdDesc(BackupKind kind, BackupStatus status);
}
