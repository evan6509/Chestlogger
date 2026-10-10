package com.chestlogger;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.ShelfBlockEntity;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import static com.chestlogger.ContainerGameTests.*;

public final class StorageCoverageGameTests {
    @GameTest(maxTicks = 50)
    public void campfireBreakAndAnimalDeathRetainContentsOnce(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CAMPFIRE);
        var campfire = helper.getBlockEntity(pos, net.minecraft.world.level.block.entity.CampfireBlockEntity.class);
        campfire.getItems().set(0, new ItemStack(Items.BEEF, 3));
        StorageAudit.acknowledge(Storage.block(campfire));
        helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(pos)), "Campfire actually broken");
        helper.assertTrue(count(at(records(helper, player), helper.absolutePos(pos)), "BREAK_CONTENTS", "minecraft:beef") == 3,
                "Non-Container campfire adapter retains its contents");
        var level = helper.getLevel();
        var donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
        donkey.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(5, 1, 5))));
        level.addFreshEntity(donkey);
        donkey.getSlot(499).set(new ItemStack(Items.CHEST));
        var inventory = ((com.chestlogger.mixin.HorseInventoryAccessor) donkey).chestlogger$getInventory();
        inventory.setItem(2, new ItemStack(Items.DIAMOND, 5));
        StorageAudit.acknowledge(Storage.entity(donkey));
        helper.assertTrue(donkey.hurtServer(level, level.damageSources().playerAttack(player), 1000), "Animal actually killed");
        helper.runAtTickTime(25, () -> {
            try {
                var rows = records(helper, player).stream().filter(r -> r.get("entity_uuid").equals(donkey.getStringUUID())).toList();
                helper.assertTrue(count(rows, "DESTROY_CONTENTS", "minecraft:diamond") == 5,
                        "Animal death identifies killer without duplicating later entity removal");
                helper.assertTrue(rows.stream().filter(r -> r.get("action").equals("DESTROY")).count() == 1,
                        "Animal death recorded once");
                helper.succeed();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @GameTest
    public void actualMinecartAndBoatPlacementIdentifyBuilder(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos rails = new BlockPos(2, 1, 2);
        helper.setBlock(rails.below(), Blocks.STONE);
        helper.setBlock(rails, Blocks.RAIL);
        var absolute = helper.absolutePos(rails);
        player.setPos(Vec3.atCenterOf(absolute).add(0, 0, -1));
        var item = new ItemStack(Items.HOPPER_MINECART);
        player.setItemInHand(InteractionHand.MAIN_HAND, item);
        var hit = new BlockHitResult(Vec3.atCenterOf(absolute), Direction.UP, absolute, false);
        helper.assertTrue(player.gameMode.useItemOn(player, helper.getLevel(), item, InteractionHand.MAIN_HAND, hit).consumesAction(),
                "Minecart actually placed using its item");
        var rows = records(helper, player).stream().filter(r -> r.get("action").equals("PLACE")
                && r.get("container").equals("minecraft:hopper_minecart")).toList();
        helper.assertTrue(rows.size() == 1 && !rows.getFirst().get("entity_uuid").isEmpty(),
                "Placed minecart has one builder record with its stable UUID");
        BlockPos boatFloor = new BlockPos(5, 0, 5);
        helper.setBlock(boatFloor, Blocks.STONE);
        player.setPos(Vec3.atCenterOf(helper.absolutePos(boatFloor)).add(0, 2, 0));
        player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.OAK_CHEST_BOAT));
        helper.assertTrue(Items.OAK_CHEST_BOAT.use(helper.getLevel(), player, InteractionHand.MAIN_HAND).consumesAction(),
                "Chest boat actually placed using its item");
        var boats = records(helper, player).stream().filter(r -> r.get("action").equals("PLACE")
                && r.get("container").equals("minecraft:oak_chest_boat")).toList();
        helper.assertTrue(boats.size() == 1 && !boats.getFirst().get("entity_uuid").isEmpty(),
                "Placed boat has one builder record with its stable UUID");
        helper.succeed();
    }

    @GameTest
    public void removingAnimalChestRetainsDiscardedInventoryAudit(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        var donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
        donkey.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
        level.addFreshEntity(donkey);
        donkey.getSlot(499).set(new ItemStack(Items.CHEST));
        var inventory = ((com.chestlogger.mixin.HorseInventoryAccessor) donkey).chestlogger$getInventory();
        inventory.setItem(2, new ItemStack(Items.DIAMOND, 5));
        StorageAudit.acknowledge(Storage.entity(donkey));
        var player = helper.makeMockServerPlayerInLevel();
        try (var scope = StorageAudit.scope(player, "COMMAND", Storage.entity(donkey))) {
            helper.assertTrue(donkey.getSlot(499).set(ItemStack.EMPTY), "Animal chest removed through native slot access");
        }
        var rows = records(helper, player).stream().filter(r -> r.get("entity_uuid").equals(donkey.getStringUUID())).toList();
        helper.assertTrue(count(rows, "COMMAND_REMOVE", "minecraft:diamond") == 5,
                "Lost slots are recorded even when the container object changes");
        helper.succeed();
    }

    @GameTest
    public void processingInventoryWithdrawalsIdentifyPlayer(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var blocks = List.of(Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.BREWING_STAND, Blocks.CRAFTER);
        for (int i = 0; i < blocks.size(); i++) {
            BlockPos pos = new BlockPos(1 + i, 1, 2);
            helper.setBlock(pos, blocks.get(i));
            Container container = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
            container.setItem(0, new ItemStack(Items.DIAMOND, 2));
            helper.useBlock(pos, player);
            helper.assertTrue(player.containerMenu != player.inventoryMenu, "Processing block menu opened");
            player.containerMenu.clicked(0, 0, ContainerInput.PICKUP, player);
            player.doCloseContainer();
            var rows = at(records(helper, player), helper.absolutePos(pos));
            helper.assertTrue(count(rows, "REMOVE", "minecraft:diamond") == 2, "Processing inventory removal attributed");
        }
        helper.assertTrue(count(records(helper, player), "REMOVE", "minecraft:diamond") == 10, "No duplicate player removals");
        helper.succeed();
    }

    @GameTest
    public void directBookshelfShelfAndJukeboxWithdrawalsIdentifyPlayer(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos booksPos = new BlockPos(1, 1, 2);
        helper.setBlock(booksPos, Blocks.CHISELED_BOOKSHELF.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        var books = helper.getBlockEntity(booksPos, ChiseledBookShelfBlockEntity.class);
        books.setItem(0, new ItemStack(Items.BOOK));
        interact(helper, player, booksPos, 0.8, 0.8);
        helper.assertTrue(books.getItem(0).isEmpty(), "Book actually removed through block interaction");
        helper.assertTrue(count(at(records(helper, player), helper.absolutePos(booksPos)), "REMOVE", "minecraft:book") == 1,
                "Bookshelf withdrawal identifies player");

        BlockPos shelfPos = new BlockPos(3, 1, 2);
        helper.setBlock(shelfPos, Blocks.OAK_SHELF.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
        var shelf = helper.getBlockEntity(shelfPos, ShelfBlockEntity.class);
        shelf.setItem(1, new ItemStack(Items.DIAMOND, 4));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        interact(helper, player, shelfPos, 0.5, 0.5);
        helper.assertTrue(shelf.getItem(1).isEmpty(), "Shelf stack actually removed");
        helper.assertTrue(count(at(records(helper, player), helper.absolutePos(shelfPos)), "REMOVE", "minecraft:diamond") == 4,
                "Shelf withdrawal identifies player");

        BlockPos jukeboxPos = new BlockPos(5, 1, 2);
        helper.setBlock(jukeboxPos, Blocks.JUKEBOX);
        var jukebox = helper.getBlockEntity(jukeboxPos, JukeboxBlockEntity.class);
        jukebox.setTheItem(new ItemStack(Items.MUSIC_DISC_13));
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        interact(helper, player, jukeboxPos, 0.5, 0.5);
        helper.assertTrue(jukebox.isEmpty(), "Disc actually ejected");
        helper.assertTrue(count(at(records(helper, player), helper.absolutePos(jukeboxPos)), "REMOVE", "minecraft:music_disc_13") == 1,
                "Disc ejection identifies player");
        helper.succeed();
    }

    @GameTest
    public void lecternButtonAndPrivateEnderInventoryAreLogged(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos lecternPos = new BlockPos(1, 1, 2);
        helper.setBlock(lecternPos, Blocks.LECTERN.defaultBlockState().setValue(BlockStateProperties.HAS_BOOK, true));
        var lectern = helper.getBlockEntity(lecternPos, LecternBlockEntity.class);
        lectern.setBook(new ItemStack(Items.WRITABLE_BOOK));
        player.setPos(Vec3.atCenterOf(helper.absolutePos(lecternPos)).add(0, 0, -1));
        helper.useBlock(lecternPos, player);
        helper.assertTrue(player.containerMenu != player.inventoryMenu, "Lectern menu opened");
        player.connection.handleContainerButtonClick(new ServerboundContainerButtonClickPacket(player.containerMenu.containerId, 3));
        helper.assertTrue(lectern.getBook().isEmpty(), "Take-book button actually removed the book");
        helper.assertTrue(count(at(records(helper, player), helper.absolutePos(lecternPos)), "REMOVE", "minecraft:writable_book") == 1,
                "Lectern button withdrawal identifies player");
        player.doCloseContainer();

        BlockPos enderPos = new BlockPos(3, 1, 2);
        helper.setBlock(enderPos, Blocks.ENDER_CHEST);
        player.getEnderChestInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        helper.useBlock(enderPos, player);
        player.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        player.doCloseContainer();
        var rows = at(records(helper, player), helper.absolutePos(enderPos));
        helper.assertTrue(count(rows, "REMOVE", "minecraft:diamond") == 5, "Ender chest withdrawal logged");
        helper.assertTrue(rows.stream().allMatch(r -> r.get("storage_owner_uuid").equals(player.getStringUUID())),
                "Ender inventory is identified by its actual private owner");
        helper.succeed();
    }

    @GameTest
    public void breakingFilledStorageRecordsContentsAndPreservedShulker(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        for (var block : List.of(Blocks.CHEST, Blocks.SHULKER_BOX, Blocks.FURNACE, Blocks.DECORATED_POT)) {
            BlockPos pos = new BlockPos(2, 1, 2);
            helper.setBlock(pos, block);
            Container storage = (Container) helper.getLevel().getBlockEntity(helper.absolutePos(pos));
            ItemStack named = new ItemStack(Items.DIAMOND, 6);
            named.set(DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Audit diamond"));
            storage.setItem(0, named);
            int start = allRecords(helper).size();
            helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(pos)), "Storage actually broken");
            var rows = allRecords(helper).subList(start, allRecords(helper).size());
            helper.assertTrue(count(rows, "BREAK_CONTENTS", "minecraft:diamond") == 6, "All broken contents recorded once");
            helper.assertTrue(rows.stream().allMatch(r -> r.get("player_uuid").equals(player.getStringUUID())),
                    "Contents attributed to the successful breaker");
            helper.assertTrue(rows.stream().anyMatch(r -> r.get("item_data").contains("Audit diamond")), "Item components retained");
        }
        helper.succeed();
    }

    @GameTest
    public void placedFilledShulkerRecordsInitialContents(GameTestHelper helper) throws IOException {
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos.below(), Blocks.STONE);
        ItemStack box = new ItemStack(Items.SHULKER_BOX);
        box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.DIAMOND, 9))));
        player.setItemInHand(InteractionHand.MAIN_HAND, box);
        BlockPos support = helper.absolutePos(pos.below());
        var hit = new BlockHitResult(Vec3.atCenterOf(support), Direction.UP, support, false);
        var context = new net.minecraft.world.item.context.BlockPlaceContext(player, InteractionHand.MAIN_HAND, box, hit);
        helper.assertTrue(((net.minecraft.world.item.BlockItem) Items.SHULKER_BOX).place(context).consumesAction(), "Filled box placed");
        helper.assertTrue(count(records(helper, player), "PLACE_CONTENTS", "minecraft:diamond") == 9, "Initial contents identify placer");
        helper.succeed();
    }

    @GameTest
    public void hopperMinecartAndVehicleWithdrawalsHaveStableIdentity(GameTestHelper helper) throws IOException {
        BlockPos chestPos = new BlockPos(2, 2, 2);
        helper.setBlock(chestPos, Blocks.CHEST);
        var chest = helper.getBlockEntity(chestPos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        var level = helper.getLevel();
        var cart = EntityTypes.HOPPER_MINECART.create(level, EntitySpawnReason.COMMAND);
        cart.setPos(Vec3.atBottomCenterOf(helper.absolutePos(chestPos.below())));
        level.addFreshEntity(cart);
        StorageAudit.acknowledge(Storage.block(chest));
        int start = allRecords(helper).size();
        helper.assertTrue(cart.suckInItems(), "Hopper minecart actually took an item from the chest");
        var transfers = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(transfers.size() == 2 && count(transfers, "HOPPER_REMOVE", "minecraft:diamond") == 1,
                "Minecart transfer logged once on each side");
        helper.assertTrue(transfers.getFirst().get("related_entity_uuid").equals(cart.getStringUUID())
                        && transfers.getLast().get("entity_uuid").equals(cart.getStringUUID()), "Moving cart has stable UUID");
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.openMenu(cart);
        player.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        player.doCloseContainer();
        helper.assertTrue(count(records(helper, player), "REMOVE", "minecraft:diamond") == 1, "Minecart menu withdrawal identifies player");
        cart.setItem(0, new ItemStack(Items.GOLD_INGOT, 7));
        StorageAudit.acknowledge(Storage.entity(cart));
        cart.destroy(level, level.damageSources().playerAttack(player));
        helper.assertTrue(count(records(helper, player), "DESTROY_CONTENTS", "minecraft:gold_ingot") == 7,
                "Destroying a filled minecart identifies the attacker and contents");
        helper.succeed();
    }

    @GameTest
    public void chestBoatAndDonkeyWithdrawalsAreLogged(GameTestHelper helper) throws IOException {
        var level = helper.getLevel();
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        var boat = EntityTypes.OAK_CHEST_BOAT.create(level, EntitySpawnReason.COMMAND);
        boat.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(2, 1, 2))));
        level.addFreshEntity(boat);
        boat.setItem(0, new ItemStack(Items.DIAMOND, 4));
        player.openMenu(boat);
        player.containerMenu.clicked(0, 0, ContainerInput.QUICK_MOVE, player);
        player.doCloseContainer();
        helper.assertTrue(count(records(helper, player), "REMOVE", "minecraft:diamond") == 4, "Chest boat withdrawal logged");
        var donkey = EntityTypes.DONKEY.create(level, EntitySpawnReason.COMMAND);
        donkey.setPos(Vec3.atCenterOf(helper.absolutePos(new BlockPos(4, 1, 2))));
        level.addFreshEntity(donkey);
        donkey.setTamed(true);
        helper.assertTrue(donkey.getSlot(499).set(new ItemStack(Items.CHEST)), "Chest attached through native inventory access");
        // Opening first refreshes aliases after attaching a chest resized the inventory.
        donkey.openCustomInventoryScreen(player);
        var inventory = ((com.chestlogger.mixin.HorseInventoryAccessor) donkey).chestlogger$getInventory();
        inventory.setItem(2, new ItemStack(Items.EMERALD, 5));
        int slot = player.containerMenu.findSlot(inventory, 2).orElseThrow();
        player.containerMenu.clicked(slot, 0, ContainerInput.QUICK_MOVE, player);
        player.doCloseContainer();
        helper.assertTrue(count(records(helper, player), "REMOVE", "minecraft:emerald") == 5, "Donkey storage withdrawal logged");
        helper.succeed();
    }

    @GameTest
    public void explosionAndPlayerPickupAreLogged(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 absolute = Vec3.atCenterOf(helper.absolutePos(pos));
        int start = allRecords(helper).size();
        helper.getLevel().explode(player, absolute.x, absolute.y, absolute.z, 4, Level.ExplosionInteraction.BLOCK);
        var rows = at(allRecords(helper).subList(start, allRecords(helper).size()), helper.absolutePos(pos));
        helper.assertTrue(count(rows, "EXPLOSION_CONTENTS", "minecraft:diamond") == 8, "Exploded contents recorded");
        helper.assertTrue(rows.stream().allMatch(r -> r.get("player_uuid").equals(player.getStringUUID())), "Known explosion source identified");
        ItemEntity item = new ItemEntity(helper.getLevel(), absolute.x, absolute.y, absolute.z, new ItemStack(Items.GOLD_INGOT, 3));
        helper.getLevel().addFreshEntity(item);
        item.setNoPickUpDelay();
        item.playerTouch(player);
        helper.assertTrue(item.isRemoved(), "Dropped item actually picked up");
        helper.assertTrue(count(records(helper, player), "PICKUP_REMOVE", "minecraft:gold_ingot") == 3, "Collector and pickup quantity recorded");
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void silentChangesAreAuditedWithoutInventingPlayer(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        StorageAudit.acknowledge(Storage.block(chest));
        int start = allRecords(helper).size();
        // A mod bypasses setItem/setChanged and mutates a stack directly.
        chest.getItem(0).shrink(3);
        helper.runAfterDelay(2, () -> {
            try {
                var rows = at(allRecords(helper).subList(start, allRecords(helper).size()), helper.absolutePos(pos));
                helper.assertTrue(count(rows, "STORAGE_REMOVE", "minecraft:diamond") == 3, "Fallback catches unsignaled inventory mutation");
                helper.assertTrue(rows.stream().allMatch(r -> r.get("player_uuid").isEmpty()), "Unknown actor remains unknown");
                helper.succeed();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @GameTest
    public void commandChangesIdentifySourceAndUnopenedLootIsNotGenerated(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setItem(0, new ItemStack(Items.DIAMOND, 8));
        StorageAudit.acknowledge(Storage.block(chest));
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos absolute = helper.absolutePos(pos);
        helper.getLevel().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack()
                        .withPermission(net.minecraft.server.permissions.PermissionSet.ALL_PERMISSIONS),
                "item replace block " + absolute.getX() + " " + absolute.getY() + " " + absolute.getZ() + " container.0 with minecraft:gold_ingot 2");
        helper.assertTrue(chest.getItem(0).is(Items.GOLD_INGOT), "Command actually changed stored items");
        helper.assertTrue(count(records(helper, player), "COMMAND_REMOVE", "minecraft:diamond") == 8
                        && count(records(helper, player), "COMMAND_ADD", "minecraft:gold_ingot") == 2, "Command source and deltas logged");
        BlockPos lootPos = new BlockPos(4, 1, 2);
        helper.setBlock(lootPos, Blocks.CHEST);
        var loot = helper.getBlockEntity(lootPos, RandomizableContainerBlockEntity.class);
        loot.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        StorageAudit.observe(Storage.block(loot));
        helper.assertTrue(loot.getLootTable() != null, "Auditing does not generate unopened loot");
        helper.succeed();
    }

    @GameTest
    public void destroyingUnopenedLootRecordsExactlyWhatVanillaDropped(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.CHEST);
        var chest = helper.getBlockEntity(pos, RandomizableContainerBlockEntity.class);
        chest.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON);
        chest.setLootTableSeed(12345);
        var player = helper.makeMockServerPlayerInLevel();
        player.gameMode.changeGameModeForPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        helper.assertTrue(chest.getLootTable() != null, "Loot remains ungenerated before the break");
        helper.assertTrue(player.gameMode.destroyBlock(helper.absolutePos(pos)), "Unopened loot chest actually broken");
        var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new net.minecraft.world.phys.AABB(helper.absolutePos(pos)).inflate(2));
        Map<String, Integer> expected = new java.util.LinkedHashMap<>();
        for (var drop : drops) if (!drop.getItem().is(Items.CHEST)) {
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(drop.getItem().getItem()).toString();
            expected.merge(id, drop.getItem().getCount(), Integer::sum);
        }
        Map<String, Integer> actual = new java.util.LinkedHashMap<>();
        for (var row : records(helper, player)) if (row.get("action").equals("BREAK_CONTENTS")) {
            actual.merge(row.get("item_id"), Integer.parseInt(row.get("quantity")), Integer::sum);
        }
        helper.assertTrue(!expected.isEmpty() && actual.equals(expected), "Generated loot matches actual drops exactly");
        helper.succeed();
    }

    @GameTest(maxTicks = 250)
    public void furnaceProcessingIsAuditedWithoutBlamingOpenPlayer(GameTestHelper helper) throws IOException {
        BlockPos pos = new BlockPos(2, 1, 2);
        helper.setBlock(pos, Blocks.FURNACE);
        var furnace = helper.getBlockEntity(pos, net.minecraft.world.level.block.entity.FurnaceBlockEntity.class);
        furnace.setItem(0, new ItemStack(Items.IRON_ORE));
        furnace.setItem(1, new ItemStack(Items.COAL));
        StorageAudit.acknowledge(Storage.block(furnace));
        var player = helper.makeMockServerPlayerInLevel();
        player.setPos(Vec3.atCenterOf(helper.absolutePos(pos)).add(0, 0, -1));
        helper.useBlock(pos, player);
        int start = allRecords(helper).size();
        helper.succeedWhen(() -> {
            helper.assertTrue(furnace.getItem(2).is(Items.IRON_INGOT), "Furnace actually smelted the ore");
            try {
                var rows = at(allRecords(helper).subList(start, allRecords(helper).size()), helper.absolutePos(pos));
                helper.assertTrue(count(rows, "STORAGE_REMOVE", "minecraft:iron_ore") == 1
                                && count(rows, "STORAGE_REMOVE", "minecraft:coal") == 1
                                && count(rows, "STORAGE_ADD", "minecraft:iron_ingot") == 1, "Processing deltas logged once");
                helper.assertTrue(rows.stream().filter(r -> !r.get("item_id").isEmpty())
                        .allMatch(r -> r.get("player_uuid").isEmpty()), "Processing never attributed to open player");
                player.doCloseContainer();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }

    @GameTest
    public void dropperAndLooseHopperPickupDoNotDisappearFromAudit(GameTestHelper helper) throws IOException {
        BlockPos sourcePos = new BlockPos(2, 1, 2);
        BlockPos targetPos = sourcePos.east();
        helper.setBlock(sourcePos, Blocks.DROPPER.defaultBlockState()
                .setValue(net.minecraft.world.level.block.DispenserBlock.FACING, Direction.EAST));
        helper.setBlock(targetPos, Blocks.CHEST);
        var source = helper.getBlockEntity(sourcePos, RandomizableContainerBlockEntity.class);
        var target = helper.getBlockEntity(targetPos, RandomizableContainerBlockEntity.class);
        source.setItem(0, new ItemStack(Items.DIAMOND, 2));
        StorageAudit.acknowledge(Storage.block(source));
        StorageAudit.acknowledge(Storage.block(target));
        int start = allRecords(helper).size();
        helper.getLevel().getBlockState(helper.absolutePos(sourcePos)).tick(helper.getLevel(), helper.absolutePos(sourcePos), helper.getLevel().getRandom());
        helper.assertTrue(target.getItem(0).getCount() == 1, "Dropper actually transferred one diamond");
        var rows = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(count(at(rows, helper.absolutePos(sourcePos)), "STORAGE_REMOVE", "minecraft:diamond") == 1
                        && count(at(rows, helper.absolutePos(targetPos)), "STORAGE_ADD", "minecraft:diamond") == 1, "Both dropper endpoints recorded");

        BlockPos hopperPos = new BlockPos(5, 1, 2);
        helper.setBlock(hopperPos, Blocks.HOPPER);
        var hopper = helper.getBlockEntity(hopperPos, net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        Vec3 absolute = Vec3.atCenterOf(helper.absolutePos(hopperPos));
        var item = new ItemEntity(helper.getLevel(), absolute.x, absolute.y, absolute.z, new ItemStack(Items.GOLD_INGOT, 3));
        helper.getLevel().addFreshEntity(item);
        start = allRecords(helper).size();
        helper.assertTrue(net.minecraft.world.level.block.entity.HopperBlockEntity.addItem(hopper, item), "Hopper actually collected dropped stack");
        rows = allRecords(helper).subList(start, allRecords(helper).size());
        helper.assertTrue(rows.size() == 2 && count(rows, "HOPPER_ADD", "minecraft:gold_ingot") == 3
                        && count(rows, "HOPPER_REMOVE", "minecraft:gold_ingot") == 3, "Pickup has balanced, unduplicated rows");
        helper.assertTrue(rows.getFirst().get("entity_uuid").equals(item.getStringUUID()), "Ground-item identity retained");
        helper.succeed();
    }

    private static void interact(GameTestHelper helper, ServerPlayer player, BlockPos pos, double x, double y) {
        BlockPos absolute = helper.absolutePos(pos);
        player.setPos(Vec3.atCenterOf(absolute).add(0, 0, -1));
        var hit = new BlockHitResult(new Vec3(absolute.getX() + x, absolute.getY() + y, absolute.getZ()), Direction.NORTH, absolute, false);
        player.gameMode.useItemOn(player, helper.getLevel(), player.getItemInHand(InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND, hit);
    }
}
