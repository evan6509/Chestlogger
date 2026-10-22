package com.chestlogger;

import com.sun.management.ThreadMXBean;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.LockSupport;

/** Opt-in server-thread measurement; never makes timing assertions in the normal test suite. */
public final class CommandAuditBenchmark {
    private static final int ROUNDS = 3;
    private static final int SAMPLES = 200;
    private final ThreadMXBean thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    private PrintWriter raw;
    private PrintWriter summary;

    @GameTest(maxTicks = 100000)
    public void commandAuditBenchmark(GameTestHelper helper) throws Exception {
        if (!Boolean.getBoolean("chestlogger.commandBenchmark")) { helper.succeed(); return; }
        Path output = Path.of(System.getProperty("chestlogger.benchmarkOutput"));
        Files.createDirectories(output);
        var server = helper.getLevel().getServer();
        var source = server.createCommandSourceStack().withSuppressedOutput();
        var commands = server.getCommands();
        var parse = commands.getDispatcher().parse("chestlogger info", source);
        Runnable command = () -> commands.performCommand(parse, "chestlogger info");
        Runnable tick = () -> ServerTickEvents.END_SERVER_TICK.invoker().onEndTick(server);
        thread.setThreadAllocatedMemoryEnabled(true);
        try (var state = new StorageAuditRegressionGameTests.AuditState();
             var samples = new PrintWriter(Files.newBufferedWriter(output.resolve("samples.csv")));
             var summaries = new PrintWriter(Files.newBufferedWriter(output.resolve("summary.csv")))) {
            raw = samples;
            summary = summaries;
            raw.println("phase,workload,inventories,round,sample,elapsed_ns,allocated_bytes,gc_count,gc_ms");
            summary.println("phase,workload,inventories,samples,p50_ms,p95_ms,p99_ms,mean_ms,mean_bytes,gc_count,gc_ms");
            Files.writeString(output.resolve("environment.txt"), "java=" + System.getProperty("java.runtime.version")
                    + "\nvm=" + System.getProperty("java.vm.name") + "\nos=" + System.getProperty("os.name")
                    + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch")
                    + "\nprocessors=" + Runtime.getRuntime().availableProcessors()
                    + "\nargs=" + ManagementFactory.getRuntimeMXBean().getInputArguments()
                    + "\nrounds=" + ROUNDS + " samples_per_round=" + SAMPLES
                    + "\ncommand=Commands.performCommand(preparsed chestlogger info, suppressed output)"
                    + "\nrepresentative=7/27 slots; 4 ordinary + 3 rich; stress=27/27 rich with nested containers\n");
            StorageAudit.clear();
            warm(command);
            measure("control", "empty", 0, command);
            for (String workload : List.of("representative", "stress")) {
                for (int count : new int[]{100, 1000, 5000}) {
                    StorageAudit.clear();
                    for (int index = 0; index < count; index++) {
                        // Real vanilla inventory and adapters, detached from chunk ticking/terrain.
                        var chest = new ChestBlockEntity(new BlockPos(index, 64, 0), Blocks.CHEST.defaultBlockState());
                        int occupied = workload.equals("stress") ? 27 : 7;
                        for (int slot = 0; slot < occupied; slot++) chest.setItem(slot, stack(index, slot, workload));
                        chest.setLevel(helper.getLevel());
                        StorageAudit.acknowledge(Storage.block(chest));
                    }
                    System.out.println("COMMAND_BENCH starting " + workload + " inventories=" + count);
                    warm(command);
                    measure("command", workload, count, command);
                    warm(tick);
                    measure("tick", workload, count, tick);
                    if (count == 1000 && workload.equals("representative")) sustained(command, tick);
                    raw.flush();
                    summary.flush();
                }
            }
            StorageAudit.clear();
        }
        helper.succeed();
    }

    private static ItemStack stack(int inventory, int slot, String workload) {
        boolean stress = workload.equals("stress");
        if (!stress && slot < 4) return new ItemStack(
                List.of(Items.DIAMOND, Items.COBBLESTONE, Items.IRON_INGOT, Items.OAK_LOG).get(slot), 32);
        var stack = new ItemStack(Items.DIAMOND, 16);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Stored variant " + inventory + ":" + slot));
        stack.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("Owner's stored supplies"),
                Component.literal("Variant " + slot), Component.literal("日本語 component text"),
                Component.literal("Inventory audit fixture"))));
        var tag = new CompoundTag();
        for (int i = 0; i < (stress ? 8 : 2); i++) tag.putString("field" + i, "value-" + inventory + "-" + slot + "x".repeat(64));
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        if (stress) {
            var nested = new ArrayList<ItemStack>();
            for (int i = 0; i < 3; i++) {
                var item = new ItemStack(Items.EMERALD, 32);
                item.set(DataComponents.CUSTOM_NAME, Component.literal("Nested " + inventory + ":" + slot + ":" + i));
                nested.add(item);
            }
            stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(nested));
        }
        return stack;
    }

    private static void warm(Runnable operation) {
        long until = System.nanoTime() + 2_000_000_000L;
        int iterations = 0;
        do { operation.run(); iterations++; } while (iterations < 100 || System.nanoTime() < until);
    }

    private void measure(String phase, String workload, int count, Runnable operation) {
        var times = new long[ROUNDS * SAMPLES];
        long bytes = 0;
        long startGc = gcCount(), startGcMs = gcMs();
        for (int round = 0; round < ROUNDS; round++) {
            // Keep rounds distinguishable in raw output without forcing GC or dropping pauses.
            for (int sample = 0; sample < SAMPLES; sample++) {
                long allocated = allocated(), gc = gcCount(), gcTime = gcMs();
                long start = System.nanoTime();
                operation.run();
                long elapsed = System.nanoTime() - start;
                long used = allocated() - allocated;
                times[round * SAMPLES + sample] = elapsed;
                bytes += used;
                raw.printf(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d%n", phase, workload, count, round,
                        sample, elapsed, used, gcCount() - gc, gcMs() - gcTime);
            }
        }
        summarize(phase, workload, count, times, bytes, gcCount() - startGc, gcMs() - startGcMs);
    }

    private void sustained(Runnable command, Runnable tick) {
        // 30 real seconds, 10 commands/s and the regular production scan at 20 ticks/s.
        // Command service time excludes pacing and tick work; pauses remain included.
        var times = new long[300];
        long bytes = 0, startGc = gcCount(), startGcMs = gcMs();
        long origin = System.nanoTime();
        for (int i = 0; i < 600; i++) {
            long deadline = origin + i * 50_000_000L;
            while (System.nanoTime() < deadline) LockSupport.parkNanos(deadline - System.nanoTime());
            tick.run();
            if (i % 2 != 0) continue;
            long allocated = allocated(), gc = gcCount(), gcTime = gcMs(), start = System.nanoTime();
            command.run();
            long elapsed = System.nanoTime() - start, used = allocated() - allocated;
            times[i / 2] = elapsed;
            bytes += used;
            raw.printf(Locale.ROOT, "sustained,representative,1000,%d,%d,%d,%d,%d,%d%n", i / 20, i / 2,
                    elapsed, used, gcCount() - gc, gcMs() - gcTime);
        }
        summarize("sustained", "representative", 1000, times, bytes, gcCount() - startGc, gcMs() - startGcMs);
    }

    private void summarize(String phase, String workload, int count, long[] times, long bytes, long gc, long gcMs) {
        double mean = Arrays.stream(times).average().orElseThrow() / 1e6;
        Arrays.sort(times);
        String row = String.format(Locale.ROOT, "%s,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%d,%d,%d", phase,
                workload, count, times.length, percentile(times, .5), percentile(times, .95),
                percentile(times, .99), mean, bytes / times.length, gc, gcMs);
        summary.println(row);
        summary.flush();
        System.out.println("COMMAND_BENCH " + row);
    }

    private static double percentile(long[] sorted, double quantile) {
        return sorted[(int) Math.ceil(sorted.length * quantile) - 1] / 1e6;
    }
    private long allocated() { return thread.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    private static long gcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionCount())).sum(); }
    private static long gcMs() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(b -> Math.max(0, b.getCollectionTime())).sum(); }
}
