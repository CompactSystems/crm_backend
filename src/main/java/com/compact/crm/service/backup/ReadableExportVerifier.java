package com.compact.crm.service.backup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

// Checks a finished readable export BEFORE it may be uploaded and marked
// SUCCEEDED, by reading the ZIP back from disk - independently of the code
// that wrote it:
//  - it opens as a ZIP and holds exactly the expected entries (no more, no
//    fewer, no unsafe names), each with the SHA-256 recorded while writing;
//  - every table CSV starts with the UTF-8 BOM, parses as CSV, has the
//    expected header and exactly the row count read in the snapshot, and every
//    record has as many fields as the header;
//  - relationships.csv parses and has the expected number of links;
//  - manifest.json parses, says restorable: false, and matches every table's
//    row count and checksum; README.txt says it is not a backup;
//  - the employees table has at least one row (an export of a CRM always has
//    the admin who made it, so an empty one means no real data was read);
//  - no password hash (bcrypt "$2a$..") appears anywhere in the ZIP.
// Any problem is a BackupFailedException naming the file, never its contents.
public final class ReadableExportVerifier {

    private static final Pattern BCRYPT = Pattern.compile("\\$2[abxy]?\\$\\d{2}\\$[./A-Za-z0-9]{53}");

    public void verify(Path zip, ReadableExporter.Result expected) throws IOException {

        try (ZipFile file = new ZipFile(zip.toFile(), StandardCharsets.UTF_8)) {

            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> all = file.entries();
            while (all.hasMoreElements()) {
                ZipEntry e = all.nextElement();
                String n = e.getName();
                if (n.contains("..") || n.startsWith("/") || n.contains("\\") || !n.startsWith(expected.rootFolder() + "/")) {
                    throw fail("it contains an unexpected file name");
                }
                if (!names.add(n)) {
                    throw fail("it contains a duplicate file");
                }
            }
            if (!names.equals(new HashSet<>(expected.entries()))) {
                throw fail("its files are not the expected ones");
            }

            Map<String, ReadableExporter.TableFile> tables = new HashMap<>();
            for (ReadableExporter.TableFile t : expected.tables()) {
                tables.put(t.entry(), t);
            }

            for (String name : expected.entries()) {
                ZipEntry entry = file.getEntry(name);
                MessageDigest digest = sha256();
                String shortName = name.substring(expected.rootFolder().length() + 1);
                try (InputStream raw = new DigestInputStream(file.getInputStream(entry), digest)) {
                    if (name.endsWith(".csv")) {
                        long[] counts = checkCsv(raw, shortName, tables.get(name));
                        ReadableExporter.TableFile t = tables.get(name);
                        long expectedRows = t != null ? t.rows() : expected.relationshipCount();
                        if (counts[0] != expectedRows) {
                            throw fail(shortName + " has " + counts[0] + " rows instead of " + expectedRows);
                        }
                    } else if (name.endsWith("manifest.json")) {
                        checkManifest(raw.readAllBytes(), expected);
                    } else {
                        String text = new String(raw.readAllBytes(), StandardCharsets.UTF_8);
                        noSecrets(text, shortName);
                        if (!text.contains("NOT A DATABASE BACKUP")) {
                            throw fail("README.txt does not say that it is not a database backup");
                        }
                    }
                    raw.transferTo(java.io.OutputStream.nullOutputStream());
                }
                String actual = HexFormat.of().formatHex(digest.digest());
                if (!actual.equals(expected.sha256ByEntry().get(name))) {
                    throw fail(shortName + " does not match its checksum");
                }
            }

            ReadableExporter.TableFile employees = expected.tables().stream()
                    .filter(t -> t.table().equals("employees")).findFirst()
                    .orElseThrow(() -> fail("it has no employees table"));
            if (employees.rows() < 1) {
                throw fail("the employees table is empty, so no real CRM data was read");
            }
        } catch (java.util.zip.ZipException e) {
            throw fail("it is not a readable ZIP file");
        }
    }

    // Returns {data records}. Checks BOM, header, field counts, and scans for secrets.
    private long[] checkCsv(InputStream raw, String name, ReadableExporter.TableFile table) throws IOException {
        Reader reader = new BufferedReader(new InputStreamReader(raw, StandardCharsets.UTF_8), 64 * 1024);
        int bom = reader.read();
        if (bom != '﻿') {
            throw fail(name + " does not start with the UTF-8 byte order mark");
        }
        CsvReader csv = new CsvReader(reader);
        List<String> header;
        try {
            header = csv.next();
            if (header == null) {
                throw fail(name + " is empty");
            }
            if (table != null && !header.equals(table.header())) {
                throw fail(name + " has an unexpected header");
            }
            long records = 0;
            List<String> r;
            while ((r = csv.next()) != null) {
                if (r.size() != header.size()) {
                    throw fail(name + " has a record with " + r.size() + " fields instead of " + header.size());
                }
                for (String v : r) {
                    if (v != null) {
                        noSecrets(v, name);
                    }
                }
                records++;
            }
            return new long[]{records};
        } catch (CsvReader.CsvFormatException e) {
            throw fail(name + " is not valid CSV (" + e.getMessage() + ")");
        }
    }

    private void checkManifest(byte[] json, ReadableExporter.Result expected) {
        JsonNode m;
        try {
            m = new ObjectMapper().readTree(json);
        } catch (IOException e) {
            throw fail("manifest.json is not valid JSON");
        }
        noSecrets(new String(json, StandardCharsets.UTF_8), "manifest.json");
        if (!m.path("restorable").isBoolean() || m.path("restorable").asBoolean()) {
            throw fail("manifest.json does not say that the export is not restorable");
        }
        JsonNode tables = m.path("tables");
        if (!tables.isArray() || tables.size() != expected.tables().size()) {
            throw fail("manifest.json does not list every table");
        }
        for (int i = 0; i < tables.size(); i++) {
            JsonNode t = tables.get(i);
            ReadableExporter.TableFile f = expected.tables().get(i);
            if (!f.table().equals(t.path("name").asText()) || t.path("rows").asLong(-1) != f.rows()
                    || !t.path("sha256").asText().equals(expected.sha256ByEntry().get(f.entry()))) {
                throw fail("manifest.json does not match table " + f.table());
            }
        }
        if (m.path("totalRows").asLong(-1) != expected.totalRows()) {
            throw fail("manifest.json has the wrong total row count");
        }
    }

    private void noSecrets(String text, String name) {
        if (BCRYPT.matcher(text).find()) {
            throw fail(name + " contains what looks like a password hash");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BackupFailedException fail(String why) {
        return new BackupFailedException("The readable export failed its check: " + why + ".");
    }
}
