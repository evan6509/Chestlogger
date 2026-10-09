package com.chestlogger.csv;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

/** One append-only UTF-8 file, flushed after every player action or hopper transfer. */
public final class CsvFile implements Closeable {
    public static final String HEADER = "event_id,timestamp,date,time,timezone,dimension,container,x,y,z,player,player_uuid,action,item_id,item_name,quantity,quantity_delta,item_data,related_x,related_y,related_z";
    public static final int COLUMN_COUNT = HEADER.split(",").length;
    private final BufferedWriter writer;

    public CsvFile(Path path) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        boolean fresh = !Files.exists(path) || Files.size(path) == 0;
        if (!fresh) {
            try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String first = reader.readLine();
                if (first != null && first.startsWith("\uFEFF")) first = first.substring(1);
                if (!HEADER.equals(first)) {
                    throw new IOException("CSV header does not match; refusing to append incompatible columns: " + path);
                }
                validateRecords(reader, path);
            }
            // A truncated row is safer to leave for repair than silently corrupting the next row.
            try (var channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
                channel.position(channel.size() - 1);
                var last = java.nio.ByteBuffer.allocate(1);
                channel.read(last);
                if (last.array()[0] != '\n') {
                    throw new IOException("CSV has an unfinished final row; repair it before restarting: " + path);
                }
            }
        }
        writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            // Excel recognizes the encoding when opening the CSV directly.
            writer.write("\uFEFF" + HEADER + "\r\n");
            writer.flush();
        }
    }

    private enum FieldState { START, UNQUOTED, QUOTED, CLOSED }

    /** Validate once on startup, keeping memory bounded even for a large log. */
    private static void validateRecords(BufferedReader reader, Path path) throws IOException {
        FieldState state = FieldState.START;
        int columns = 1;
        long record = 2;
        boolean recordStarted = false;
        boolean expectLineFeed = false;
        char[] buffer = new char[64 * 1024];
        int length;
        while ((length = reader.read(buffer)) != -1) {
            for (int i = 0; i < length; i++) {
                char c = buffer[i];
                if (state == FieldState.QUOTED) {
                    if (c == '"') state = FieldState.CLOSED;
                    continue;
                }
                if (expectLineFeed) {
                    if (c != '\n') throw malformed(path, record, "carriage return without a line feed");
                    expectLineFeed = false;
                } else if (c == '\r') {
                    expectLineFeed = true;
                    continue;
                }
                if (c == '\n') {
                    if (columns != COLUMN_COUNT) {
                        throw malformed(path, record, "expected " + COLUMN_COUNT + " columns, found " + columns);
                    }
                    state = FieldState.START;
                    columns = 1;
                    recordStarted = false;
                    record++;
                    continue;
                }
                recordStarted = true;
                if (c == ',') {
                    columns++;
                    if (columns > COLUMN_COUNT) throw malformed(path, record, "too many columns");
                    state = FieldState.START;
                } else if (c == '"') {
                    if (state == FieldState.UNQUOTED) throw malformed(path, record, "quote inside an unquoted field");
                    // A quote after a closing quote is an escaped quote within the same field.
                    state = FieldState.QUOTED;
                } else {
                    if (state == FieldState.CLOSED) throw malformed(path, record, "text after a closing quote");
                    state = FieldState.UNQUOTED;
                }
            }
        }
        if (state == FieldState.QUOTED) throw malformed(path, record, "unfinished quoted field");
        if (recordStarted || expectLineFeed) throw malformed(path, record, "unfinished final row");
    }

    private static IOException malformed(Path path, long record, String reason) {
        return new IOException("CSV record " + record + " has " + reason
                + "; repair it before restarting: " + path);
    }

    public synchronized void append(List<List<CsvCell>> rows) throws IOException {
        for (List<CsvCell> row : rows) {
            if (row.size() != COLUMN_COUNT) throw new IllegalArgumentException("Incorrect CSV column count");
        }
        for (List<CsvCell> row : rows) {
            writer.write(row.stream().map(CsvCell::encoded).collect(Collectors.joining(",")));
            writer.write("\r\n");
        }
        writer.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        writer.close();
    }
}
