package com.chestlogger;

import com.chestlogger.mixin.HorseInventoryAccessor;
import com.chestlogger.mixin.LecternInventoryAccessor;
import com.chestlogger.mixin.NautilusInventoryAccessor;
import com.chestlogger.mixin.EnderChestInventoryAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.nautilus.AbstractNautilus;
import net.minecraft.world.entity.vehicle.ContainerEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;

import java.util.List;
import java.util.function.Supplier;

/** A physical inventory, including inventories exposed through a separate menu container. */
public final class Storage {
    public final Object owner;
    public final Container inventory;
    private final Supplier<Iterable<ItemStack>> items;
    private final Supplier<Level> level;
    private final Supplier<BlockPos> position;
    private final String type;
    public final String entityUuid;
    public final String ownerUuid;

    private Storage(Object owner, Container inventory, Supplier<Iterable<ItemStack>> items,
                    Supplier<Level> level, Supplier<BlockPos> position, String type, String entityUuid, String ownerUuid) {
        this.owner = owner;
        this.inventory = inventory;
        this.items = items;
        this.level = level;
        this.position = position;
        this.type = type;
        this.entityUuid = entityUuid;
        this.ownerUuid = ownerUuid;
    }

    public static Storage block(BlockEntity entity) {
        if (entity == null || entity.getLevel() == null || entity.getLevel().isClientSide()) return null;
        Container container = entity instanceof Container c ? c
                : entity instanceof LecternBlockEntity lectern ? ((LecternInventoryAccessor) lectern).chestlogger$getInventory() : null;
        Supplier<Iterable<ItemStack>> items = container != null ? () -> container
                : entity instanceof CampfireBlockEntity campfire ? campfire::getItems
                : entity instanceof EnderChestBlockEntity ? () -> List.of() : null;
        if (items == null) return null;
        return new Storage(entity, container, items, entity::getLevel, entity::getBlockPos,
                BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString(), "", "");
    }

    public static Storage entity(Entity entity) {
        if (entity.level().isClientSide()) return null;
        Container container = entityInventory(entity);
        if (container == null) return null;
        return new Storage(entity, container, () -> entityInventory(entity), entity::level, entity::blockPosition,
                BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), entity.getStringUUID(), "");
    }

    private static Container entityInventory(Entity entity) {
        return entity instanceof Container c ? c
                : entity instanceof AbstractHorse horse ? ((HorseInventoryAccessor) horse).chestlogger$getInventory()
                : entity instanceof AbstractNautilus nautilus ? ((NautilusInventoryAccessor) nautilus).chestlogger$getInventory() : null;
    }

    public static Storage ender(ServerPlayer player) {
        var inventory = player.getEnderChestInventory();
        return new Storage(inventory, inventory, () -> inventory, player::level, () -> {
            var chest = ((EnderChestInventoryAccessor) inventory).chestlogger$getActiveChest();
            return chest == null ? player.blockPosition() : chest.getBlockPos();
        }, "minecraft:ender_chest", "", player.getStringUUID());
    }

    public static Storage item(ItemEntity item) {
        return new Storage(item, null, () -> List.of(item.getItem()), item::level, item::blockPosition,
                "minecraft:item", item.getStringUUID(), "");
    }

    public Level level() { return level.get(); }
    public BlockPos pos() { return position.get(); }
    public String type() {
        return owner instanceof BlockEntity entity
                ? BuiltInRegistries.BLOCK.getKey(entity.getBlockState().getBlock()).toString() : type;
    }
    public boolean lootPending() {
        return owner instanceof RandomizableContainer random && random.getLootTable() != null
                || owner instanceof ContainerEntity vehicle && vehicle.getContainerLootTable() != null;
    }
    public java.util.Map<InventorySnapshot.ItemKey, Integer> contents() {
        return lootPending() ? null : InventorySnapshot.contents(items.get());
    }

    @Override public boolean equals(Object other) { return other instanceof Storage storage && owner == storage.owner; }
    @Override public int hashCode() { return System.identityHashCode(owner); }
}
