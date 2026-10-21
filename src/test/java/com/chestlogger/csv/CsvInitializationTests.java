package com.chestlogger.csv;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.chestlogger.csv.CsvTests.check;

public final class CsvInitializationTests {
    public static void main(String[] args) throws Exception {
        // Linux's full device deterministically fails the initial header flush.
        // The repository's Linux CI also exposes the descriptor table for checking cleanup.
        Path full = Path.of("/dev/full");
        Path descriptors = Path.of("/proc/self/fd");
        if (!Files.exists(full) || !Files.isDirectory(descriptors)) {
            System.out.println("Header-failure descriptor check requires /dev/full and /proc/self/fd; skipped.");
            return;
        }
        Path path = Files.createTempDirectory("chestlogger-header-failure-").resolve("chestlog.csv");
        Files.createSymbolicLink(path, full);
        try {
            long before = openDescriptors(full, descriptors);
            try (var ignored = new CsvFile(path)) {
                throw new AssertionError("Header flush to full device must fail");
            } catch (IOException expected) {
                check(expected.getMessage().contains("No space left on device"), "Original header failure is preserved");
            }
            check(openDescriptors(full, descriptors) == before, "Failed header initialization closes its writer");
        } finally {
            Files.delete(path);
            Files.delete(path.getParent());
        }
        System.out.println("CSV header initialization failure check passed.");
    }

    private static long openDescriptors(Path target, Path descriptors) throws IOException {
        try (var entries = Files.list(descriptors)) {
            return entries.filter(entry -> {
                try { return Files.readSymbolicLink(entry).equals(target); }
                catch (IOException vanished) { return false; }
            }).count();
        }
    }
}
