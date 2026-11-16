package com.compact.crm.service.backup;

import java.io.IOException;
import java.io.Writer;
import java.util.List;
import java.util.regex.Pattern;

// RFC 4180 CSV for the readable export, written for Excel:
//  - the caller writes the UTF-8 byte order mark (BOM) first, so Excel shows
//    accents and non-Latin text correctly when the file is double-clicked;
//  - records end with CRLF; a field is quoted when it contains a comma, a
//    double quote, CR or LF, or starts/ends with a space; quotes are doubled;
//  - NULL is an empty field, an empty string is "" (quoted), so the two stay
//    distinguishable for tools that care;
//  - spreadsheet formula injection: a text value that Excel/LibreOffice would
//    run as a formula (it starts with =, @, a tab or CR, or with + / - and is
//    not a plain number or phone number) gets a leading apostrophe ('), which
//    makes the spreadsheet show it as text. guardedCells() counts how often
//    that happened; the README explains it.
public final class CsvWriter {

    // "+91 98765 43210", "-12.5", "+44 (20) 7946-0958": numbers and phone
    // numbers stay exactly as stored
    private static final Pattern PLAIN_NUMBER_OR_PHONE = Pattern.compile("[+-]?[0-9][0-9 ().-]*");

    private final Writer out;
    private long guardedCells;

    public CsvWriter(Writer out) {
        this.out = out;
    }

    // One record. Each value is either null (NULL), or a String; text values
    // (isText = true for that column) are checked by the formula guard.
    public void writeRecord(List<String> values, boolean[] isText) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                out.write(',');
            }
            String v = values.get(i);
            if (v == null) {
                continue;
            }
            if (isText == null || isText[i]) {
                String guarded = guard(v);
                if (!guarded.equals(v)) {
                    guardedCells++;
                }
                v = guarded;
            }
            out.write(escape(v));
        }
        out.write("\r\n");
    }

    public void writeHeader(List<String> names) throws IOException {
        writeRecord(names, null);
    }

    public long guardedCells() {
        return guardedCells;
    }

    static String guard(String v) {
        if (v.isEmpty()) {
            return v;
        }
        char c = v.charAt(0);
        boolean risky = c == '=' || c == '@' || c == '\t' || c == '\r'
                || ((c == '+' || c == '-') && !PLAIN_NUMBER_OR_PHONE.matcher(v).matches());
        return risky ? "'" + v : v;
    }

    static String escape(String v) {
        if (v.isEmpty()) {
            return "\"\"";
        }
        boolean quote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0
                || v.charAt(0) == ' ' || v.charAt(v.length() - 1) == ' ';
        if (!quote) {
            return v;
        }
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }
}
