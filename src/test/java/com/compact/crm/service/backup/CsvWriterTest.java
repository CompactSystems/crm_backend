package com.compact.crm.service.backup;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvWriterTest {

    private static String write(boolean[] text, List<String>... records) throws IOException {
        StringWriter out = new StringWriter();
        CsvWriter w = new CsvWriter(out);
        for (List<String> r : records) {
            w.writeRecord(r, text);
        }
        return out.toString();
    }

    @Test
    void quotesOnlyWhatNeedsQuoting_doublesQuotes_andEndsRecordsWithCrlf() throws IOException {
        String csv = write(null,
                List.of("plain", "a,b", "say \"hi\"", "line1\nline2", "cr\rhere", " lead", "trail ", "ünïcødé ✓ 漢字"));
        assertThat(csv).isEqualTo("plain,\"a,b\",\"say \"\"hi\"\"\",\"line1\nline2\",\"cr\rhere\",\" lead\",\"trail \",ünïcødé ✓ 漢字\r\n");
    }

    @Test
    void nullIsAnEmptyField_butAnEmptyStringIsQuoted() throws IOException {
        assertThat(write(null, Arrays.asList(null, "", "x", null))).isEqualTo(",\"\",x,\r\n");
    }

    @Test
    void formulaGuard_neutralisesSpreadsheetFormulas_butKeepsNumbersAndPhones() {
        assertThat(CsvWriter.guard("=HYPERLINK(\"http://evil\",\"x\")")).isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
        assertThat(CsvWriter.guard("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.guard("+cmd|' /C calc'!A0")).isEqualTo("'+cmd|' /C calc'!A0");
        assertThat(CsvWriter.guard("-2+3+cmd")).isEqualTo("'-2+3+cmd");
        assertThat(CsvWriter.guard("\t=1")).isEqualTo("'\t=1");
        assertThat(CsvWriter.guard("\r=1")).isEqualTo("'\r=1");

        assertThat(CsvWriter.guard("+91 98765 43210")).isEqualTo("+91 98765 43210");
        assertThat(CsvWriter.guard("+919876543210")).isEqualTo("+919876543210");
        assertThat(CsvWriter.guard("-12.50")).isEqualTo("-12.50");
        assertThat(CsvWriter.guard("+44 (20) 7946-0958")).isEqualTo("+44 (20) 7946-0958");
        assertThat(CsvWriter.guard("Normal text = fine")).isEqualTo("Normal text = fine");
        assertThat(CsvWriter.guard("")).isEmpty();
    }

    @Test
    void theGuard_appliesToTextColumnsOnly_andIsCounted() throws IOException {
        StringWriter out = new StringWriter();
        CsvWriter w = new CsvWriter(out);
        w.writeRecord(List.of("=1+1", "-5"), new boolean[]{true, false});
        w.writeRecord(List.of("@x", "-5"), new boolean[]{true, false});
        assertThat(out.toString()).isEqualTo("'=1+1,-5\r\n'@x,-5\r\n");
        assertThat(w.guardedCells()).isEqualTo(2);
    }

    @Test
    void theReader_readsBackExactlyWhatTheWriterWrote() throws IOException {
        List<String> record = Arrays.asList("id", null, "", "a,b", "q\"q", "multi\r\nline", " x ", "漢字");
        String csv = write(null, record, List.of("2", "y"));

        CsvReader r = new CsvReader(new StringReader(csv));
        assertThat(r.next()).isEqualTo(record);
        assertThat(r.next()).containsExactly("2", "y");
        assertThat(r.next()).isNull();
    }

    @Test
    void theReader_rejectsMalformedCsv() {
        assertThatThrownBy(() -> readAll("\"unterminated")).isInstanceOf(CsvReader.CsvFormatException.class);
        assertThatThrownBy(() -> readAll("ab\"c\r\n")).isInstanceOf(CsvReader.CsvFormatException.class);
        assertThatThrownBy(() -> readAll("\"a\"b\r\n")).isInstanceOf(CsvReader.CsvFormatException.class);
    }

    private static List<List<String>> readAll(String csv) throws IOException {
        CsvReader r = new CsvReader(new StringReader(csv));
        List<List<String>> out = new ArrayList<>();
        List<String> rec;
        while ((rec = r.next()) != null) {
            out.add(rec);
        }
        return out;
    }
}
