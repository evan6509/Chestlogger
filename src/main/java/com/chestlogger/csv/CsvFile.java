package com.chestlogger.csv;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;

/** One append-only UTF-8 file, flushed after every player action. */
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
