package com.chestlogger.csv;

/** Text is protected against spreadsheet formulas; numeric cells stay numeric. */
public record CsvCell(String value, boolean numeric) {
    public static CsvCell text(String value) {
        return new CsvCell(value == null ? "" : value, false);
    }

    public static CsvCell number(int value) {
        return new CsvCell(Integer.toString(value), true);
    }

    public String encoded() {
        String safe = value;
        if (!numeric && !safe.isEmpty()) {
            // Spaces can hide a formula prefix in spreadsheet importers.
            String trimmed = safe.stripLeading();
            if ((!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)
                    || "\t\r\n".indexOf(safe.charAt(0)) >= 0) {
                safe = "'" + safe;
            }
        }
        if (safe.contains(",") || safe.contains("\"") || safe.contains("\r") || safe.contains("\n")) {
            return "\"" + safe.replace("\"", "\"\"") + "\"";
        }
        return safe;
    }
}
