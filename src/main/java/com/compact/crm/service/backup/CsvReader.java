package com.compact.crm.service.backup;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

// Minimal RFC 4180 reader, used to check a finished readable export
// independently of CsvWriter (ReadableExportVerifier) and by tests. Quoted
// fields may contain commas, doubled quotes and line breaks. An unquoted empty
// field is returned as null (NULL), a quoted empty field as "". A quote inside
// an unquoted field, or a missing closing quote, is a format error.
public final class CsvReader {

    private final Reader in;
    private int peeked = -2;

    public CsvReader(Reader in) {
        this.in = in;
    }

    public static final class CsvFormatException extends IOException {
        public CsvFormatException(String message) {
            super(message);
        }
    }

    private int read() throws IOException {
        if (peeked != -2) {
            int c = peeked;
            peeked = -2;
            return c;
        }
        return in.read();
    }

    private int peek() throws IOException {
        if (peeked == -2) {
            peeked = in.read();
        }
        return peeked;
    }

    // The next record, or null at the end of the input.
    public List<String> next() throws IOException {
        if (peek() == -1) {
            return null;
        }
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        while (true) {
            int c = read();
            if (quoted) {
                if (c == -1) {
                    throw new CsvFormatException("unterminated quoted field");
                }
                if (c == '"') {
                    if (peek() == '"') {
                        read();
                        field.append('"');
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append((char) c);
                }
                continue;
            }
            if (c == '"') {
                if (field.length() > 0 || wasQuoted) {
                    throw new CsvFormatException("quote inside an unquoted field");
                }
                quoted = true;
                wasQuoted = true;
            } else if (c == ',') {
                fields.add(value(field, wasQuoted));
                field.setLength(0);
                wasQuoted = false;
            } else if (c == '\r' || c == '\n' || c == -1) {
                if (c == '\r' && peek() == '\n') {
                    read();
                }
                fields.add(value(field, wasQuoted));
                return fields;
            } else {
                if (wasQuoted) {
                    throw new CsvFormatException("text after a closing quote");
                }
                field.append((char) c);
            }
        }
    }

    private static String value(StringBuilder field, boolean wasQuoted) {
        return field.length() == 0 && !wasQuoted ? null : field.toString();
    }
}
