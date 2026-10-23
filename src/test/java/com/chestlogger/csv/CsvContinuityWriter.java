package com.chestlogger.csv;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Separate JVM per target/restart; uses only the production CSV implementation. */
public final class CsvContinuityWriter {
    public static void main(String[] args) throws Exception {
        Path world = Path.of(args[0]);
        Path path = world.resolve("ChestLog/chestlog.csv");
        String target = args[1];
        String event = UUID.randomUUID().toString();
        try (var file = new CsvFile(path)) {
            file.append(List.of(row(event, target, "HOPPER_REMOVE", -3), row(event, target, "HOPPER_ADD", 3)));
        }
        // Existing invalid files must be rejected without changing a byte, on every restart.
        for (String malformed : List.of("wrong,schema\n", CsvFile.HEADER + "\r\n\"unfinished")) {
            Path bad = world.resolve("malformed.csv");
            Files.writeString(bad, malformed);
            try (var ignored = new CsvFile(bad)) { throw new AssertionError("Malformed CSV accepted"); }
            catch (java.io.IOException expected) { }
            if (!Files.readString(bad).equals(malformed)) throw new AssertionError("Malformed CSV changed");
        }
    }
    private static List<CsvCell> row(String event, String target, String action, int delta) {
        var row = new ArrayList<CsvCell>();
        for (int i = 0; i < CsvFile.COLUMN_COUNT; i++) row.add(CsvCell.text(""));
        row.set(0, CsvCell.text(event));
        row.set(5, CsvCell.text(delta < 0 ? "minecraft:overworld" : "minecraft:the_nether"));
        row.set(6, CsvCell.text("minecraft:hopper"));
        row.set(12, CsvCell.text(action));
        row.set(13, CsvCell.text("minecraft:diamond"));
        row.set(14, CsvCell.text("Diamonds, \"日本語\"\r\n" + target));
        row.set(15, CsvCell.number(Math.abs(delta)));
        row.set(16, CsvCell.number(delta));
        row.set(17, CsvCell.text("{\"id\":\"minecraft:diamond\",\"count\":1}"));
        return row;
    }
}
