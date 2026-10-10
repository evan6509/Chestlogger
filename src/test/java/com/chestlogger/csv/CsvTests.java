package com.chestlogger.csv;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class CsvTests {
    public static void main(String[] args) throws Exception {
        check(CsvCell.number(-340).encoded().equals("-340"), "Negative coordinates remain numbers");
        for (String value : List.of("=1+1", "+cmd", "-text", "@SUM(A1)", "  =1+1", "\tformula")) {
            check(CsvCell.text(value).encoded().startsWith("'"), "Formula prefix is protected: " + value);
        }

        var changes = InventoryDiff.between(Map.of("diamond", 8, "iron", 6),
                Map.of("diamond", 3, "gold", 2));
        check(changes.contains(new InventoryDiff.Change<>("diamond", -5)), "Partial removal count");
        check(changes.contains(new InventoryDiff.Change<>("iron", -6)), "Whole-stack removal count");
        check(changes.contains(new InventoryDiff.Change<>("gold", 2)), "New item addition count");
        check(changes.size() == 3, "Exactly one delta per item");
        check(InventoryDiff.between(Map.of("diamond", 3), Map.of("diamond", 3)).isEmpty(), "No false change");

        Path folder = Files.createTempDirectory("chestlogger-csv-tests-");
        Path path = folder.resolve("ChestLog/chestlog.csv");
        String unusual = "Diamonds, \"rare\"\r\nUnicode: 日本語 💎";
        try (CsvFile file = new CsvFile(path)) {
            file.append(List.of(row(unusual)));
            // A flush makes the action readable while the server still has the writer open.
            check(parse(path).size() == 2, "Written action is immediately readable");
            try {
                file.append(List.of(List.of(CsvCell.text("invalid"))));
                throw new AssertionError("Invalid column count must fail");
            } catch (IllegalArgumentException expected) { }
        }
        try (CsvFile file = new CsvFile(path)) { file.append(List.of(row("second event"))); }
        var parsed = parse(path);
        check(parsed.size() == 3, "Restart appends without repeating the header");
        check(parsed.get(1).get(0).equals(unusual), "Quotes, commas, newlines, and Unicode round-trip");
        check(parsed.get(2).get(0).equals("second event"), "Existing rows preserved");
        check(Files.readString(path).startsWith("\uFEFF"), "Excel encoding marker");
        check(parsed.stream().allMatch(r -> r.size() == CsvFile.COLUMN_COUNT), "Every row matches schema");

        Path invalid = folder.resolve("wrong.csv");
        Files.writeString(invalid, "old,schema\nkeep,this\n");
        expectRejected(invalid);
        check(Files.readString(invalid).equals("old,schema\nkeep,this\n"), "Wrong schema left untouched");
        Files.writeString(invalid, CsvFile.HEADER + "\nunfinished");
        expectRejected(invalid);
        Files.writeString(invalid, CsvFile.HEADER + "\n\"partial\n");
        expectRejected(invalid);

        String emptyColumns = ",".repeat(CsvFile.COLUMN_COUNT - 1);
        String validRecord = "good" + emptyColumns + "\n";
        for (String malformed : List.of(
                "\"partial\r\n",
                emptyColumns + "\"partial\n",
                "short\n",
                ",".repeat(CsvFile.COLUMN_COUNT) + "\n",
                "bare\"quote" + emptyColumns + "\n",
                "\"closed\"text" + emptyColumns + "\n",
                "\"closed\"text" + emptyColumns + "\n" + validRecord,
                "\n" + validRecord,
                validRecord + "\r",
                "good\rbad" + emptyColumns + "\n",
                "\"closed\"" + emptyColumns)) {
            Files.writeString(invalid, CsvFile.HEADER + "\n" + malformed);
            expectRejected(invalid);
        }

        Path valid = folder.resolve("valid.csv");
        Files.writeString(valid, CsvFile.HEADER + "\n");
        try (CsvFile file = new CsvFile(valid)) { file.append(List.of(row("header only"))); }
        // LF record endings and multiline quoted fields are valid, including the last column.
        String multilineRecord = "\"line one\nline two\"" + ",".repeat(CsvFile.COLUMN_COUNT - 2)
                + ",\"last \"\"quote\"\"\r\nline\"\n";
        Files.writeString(valid, CsvFile.HEADER + "\n" + multilineRecord);
        try (CsvFile file = new CsvFile(valid)) { file.append(List.of(row("after multiline"))); }
        var multiline = parse(valid);
        check(multiline.size() == 3, "Valid LF and multiline records accept appends");
        check(multiline.get(1).size() == CsvFile.COLUMN_COUNT, "Multiline record has all columns");
        check(multiline.get(1).get(CsvFile.COLUMN_COUNT - 1).equals("last \"quote\"\r\nline"), "Final quoted field preserved");

        Path legacy = folder.resolve("legacy.csv");
        String legacyRow = "\"old, \"\"quoted\"\"\r\n日本語\"" + ",".repeat(20) + "\r\n";
        String legacyText = "\uFEFF" + CsvFile.LEGACY_HEADER + "\r\n" + legacyRow;
        Files.writeString(legacy, legacyText);
        try (CsvFile file = new CsvFile(legacy)) { file.append(List.of(row("after upgrade"))); }
        var upgraded = parse(legacy);
        check(upgraded.size() == 3 && upgraded.getFirst().equals(List.of(CsvFile.HEADER.split(","))), "Old schema upgrades once");
        check(upgraded.get(1).getFirst().equals("old, \"quoted\"\r\n日本語"), "Upgrade preserves multiline fields");
        check(upgraded.get(1).size() == CsvFile.COLUMN_COUNT && upgraded.get(1).subList(21, 24).stream().allMatch(String::isEmpty),
                "Old rows receive blank new identity columns");
        try (var backups = Files.list(folder)) {
            var backup = backups.filter(p -> p.getFileName().toString().startsWith("legacy.csv.schema21-")).findFirst().orElseThrow();
            check(Files.readString(backup).equals(legacyText), "Upgrade keeps an exact original backup");
        }
        Files.writeString(invalid, CsvFile.LEGACY_HEADER + "\n\"partial\n");
        expectRejected(invalid);
        Files.writeString(invalid, CsvFile.LEGACY_HEADER);
        expectRejected(invalid);

        Path large = folder.resolve("large.csv");
        // Escaped quotes straddle the validator's buffer boundary; the field spans several buffers.
        String largeValue = "a".repeat(64 * 1024 - 2) + "\"\n💎" + "more, \"text\"\r\n".repeat(12000);
        try (CsvFile file = new CsvFile(large)) { file.append(List.of(row(largeValue))); }
        try (CsvFile file = new CsvFile(large)) { file.append(List.of(row("after large field"))); }
        var largeRows = parse(large);
        check(largeRows.size() == 3 && largeRows.get(1).get(0).equals(largeValue), "Large quoted field round-trip");

        Path concurrent = folder.resolve("concurrent.csv");
        try (CsvFile file = new CsvFile(concurrent)) {
            List<Thread> workers = new ArrayList<>();
            List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
            for (int t = 0; t < 4; t++) {
                int id = t;
                Thread thread = new Thread(() -> {
                    try {
                        for (int n = 0; n < 25; n++) file.append(List.of(row(id + ":" + n)));
                    } catch (Throwable e) { failures.add(e); }
                });
                workers.add(thread);
                thread.start();
            }
            for (Thread worker : workers) worker.join();
            check(failures.isEmpty(), "Concurrent writes succeeded");
        }
        check(parse(concurrent).size() == 101, "Concurrent records are intact");
        System.out.println("CSV checks passed: deltas, escaping, Unicode, formulas, flush, restart, malformed records, large fields, concurrent appends.");
    }

    private static List<CsvCell> row(String first) {
        List<CsvCell> cells = new ArrayList<>();
        cells.add(CsvCell.text(first));
        while (cells.size() < CsvFile.COLUMN_COUNT) cells.add(CsvCell.text(""));
        return cells;
    }

    private static void expectRejected(Path path) throws Exception {
        byte[] original = Files.readAllBytes(path);
        try (CsvFile ignored = new CsvFile(path)) {
            throw new AssertionError("Malformed CSV must be rejected");
        } catch (IOException expected) { }
        check(java.util.Arrays.equals(original, Files.readAllBytes(path)), "Rejected file left untouched");
    }

    public static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Independent reader used to check the produced file, including quoted newlines. */
    public static List<List<String>> parse(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' && !quoted) {
                row.add(cell.toString());
                rows.add(List.copyOf(row));
                row.clear();
                cell.setLength(0);
            } else if (c != '\r' || quoted) cell.append(c);
        }
        if (quoted || !row.isEmpty() || !cell.isEmpty()) throw new IOException("Unfinished CSV record");
        return rows;
    }
}
