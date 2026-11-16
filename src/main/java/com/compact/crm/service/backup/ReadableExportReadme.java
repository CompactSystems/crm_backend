package com.compact.crm.service.backup;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

// README.txt of the readable export: what the ZIP is (and is not), how to open
// it, what every file holds and what was deliberately left out, and why.
final class ReadableExportReadme {

    private ReadableExportReadme() {
    }

    static String text(long exportId, Instant createdAt, String snapshot, String crmVersion,
                       List<ReadableExporter.TableFile> files, ReadableExportPolicy policy, Set<String> presentTables,
                       long totalRows) {

        String nl = "\r\n";
        StringBuilder s = new StringBuilder();
        s.append("CRM READABLE DATA EXPORT #").append(exportId).append(nl);
        s.append("=================================").append(nl).append(nl);
        s.append("THIS IS NOT A DATABASE BACKUP.").append(nl);
        s.append("This ZIP is a readable copy of the CRM's records for viewing, filtering and checking,").append(nl);
        s.append("for example in Excel. It CANNOT be used to restore the CRM: it has no database structure,").append(nl);
        s.append("constraints or settings, and some columns are deliberately withheld (see below).").append(nl);
        s.append("To restore the CRM, use a full backup (.dump file) from the Backups page.").append(nl).append(nl);

        s.append("Created (UTC):      ").append(DateTimeFormatter.ISO_INSTANT.format(createdAt)).append(nl);
        s.append("Snapshot:           ").append(snapshot).append(nl);
        s.append("                    All files show the data as it was at one single moment.").append(nl);
        s.append("CRM schema version: ").append(crmVersion == null ? "unknown" : crmVersion).append(nl);
        s.append("Tables exported:    ").append(files.size()).append(" (").append(totalRows).append(" rows in total)").append(nl).append(nl);

        s.append("CONFIDENTIAL").append(nl);
        s.append("------------").append(nl);
        s.append("The files contain personal and business data (names, phone numbers, e-mail addresses,").append(nl);
        s.append("addresses, GST numbers, deal values). Keep them on encrypted, access-controlled storage,").append(nl);
        s.append("do not forward them outside the company, and delete them when no longer needed.").append(nl).append(nl);

        s.append("HOW TO OPEN").append(nl);
        s.append("-----------").append(nl);
        s.append("1. Extract the ZIP (right-click > Extract All).").append(nl);
        s.append("2. Open any file in the \"tables\" folder in Excel (double-click). One file = one table,").append(nl);
        s.append("   one row = one record, one column = one field.").append(nl);
        s.append("3. Long phone numbers may be shown by Excel as 9.88E+09. To see them exactly, use").append(nl);
        s.append("   Data > From Text/CSV and set the column type to Text. The file itself is exact.").append(nl).append(nl);

        s.append("HOW RECORDS ARE LINKED").append(nl);
        s.append("----------------------").append(nl);
        s.append("Each record has an \"id\". A column ending in _id holds the id of a record in another table,").append(nl);
        s.append("e.g. leads.assigned_employee_id = employees.id. Next to such a column you will usually find a").append(nl);
        s.append("readable name column, e.g. assigned_employee__name. relationships.csv lists every link.").append(nl).append(nl);

        s.append("CONVENTIONS").append(nl);
        s.append("-----------").append(nl);
        s.append("- Encoding UTF-8 (with byte order mark), comma-separated, standard CSV quoting.").append(nl);
        s.append("- Empty cell = no value. Dates are YYYY-MM-DD, date/times YYYY-MM-DD HH:MM:SS, exactly as the").append(nl);
        s.append("  CRM stores them (no time zone; server time zone ").append(ZoneId.systemDefault().getId()).append(").").append(nl);
        s.append("- ").append(ReadableExportPolicy.WITHHELD).append(" = a value exists but is deliberately not included (see below).").append(nl);
        s.append("- Text that a spreadsheet would run as a formula (starting with =, @, + or -) starts with an").append(nl);
        s.append("  added apostrophe ('), so it is shown as text and never executed. Plain numbers and phone").append(nl);
        s.append("  numbers such as +91 98765 43210 are not changed.").append(nl);
        s.append("- manifest.json lists every file with its row count, columns and SHA-256 checksum.").append(nl).append(nl);

        s.append("TABLES").append(nl);
        s.append("------").append(nl);
        for (ReadableExporter.TableFile f : files) {
            ReadableExportPolicy.TableRule rule = policy.table(f.table()).orElseThrow();
            s.append("tables/").append(f.table()).append(".csv  (").append(f.rows()).append(" rows)").append(nl);
            s.append("    ").append(rule.description()).append(nl);
        }
        s.append(nl);

        s.append("WITHHELD COLUMNS (present, but every value reads ").append(ReadableExportPolicy.WITHHELD).append(")").append(nl);
        s.append("----------------------------------------------------------------").append(nl);
        boolean any = false;
        for (ReadableExporter.TableFile f : files) {
            ReadableExportPolicy.TableRule rule = policy.table(f.table()).orElseThrow();
            for (String col : f.withheldColumns()) {
                s.append(f.table()).append('.').append(col).append(": ").append(rule.columns().get(col).reason()).append(nl);
                any = true;
            }
        }
        if (!any) {
            s.append("(none)").append(nl);
        }
        s.append(nl);

        s.append("TABLES NOT INCLUDED").append(nl);
        s.append("-------------------").append(nl);
        any = false;
        for (ReadableExportPolicy.ExcludedTable x : policy.excludedTables()) {
            if (presentTables.contains(x.name())) {
                s.append(x.name()).append(": ").append(x.reason()).append(nl);
                any = true;
            }
        }
        if (!any) {
            s.append("(none)").append(nl);
        }
        s.append("Uploaded document files (brochures, company profiles, attachments) are not included;").append(nl);
        s.append("tables/documents.csv lists their names and details only.").append(nl);
        return s.toString();
    }
}
