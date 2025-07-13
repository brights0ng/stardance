package net.starlight.stardance.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Interface for block entities that exist on grids.
 */
public interface GridBlockEntity {
    /**
     * Called every server tick to update the block entity.
     */
    void tick(Level world, BlockPos pos, BlockState state);
    
    /**
     * Writes the block entity data to NBT for persistence.
     */
    CompoundTag writeNbt(CompoundTag nbt);
    
    /**
     * Reads the block entity data from NBT.
     */
    void readNbt(CompoundTag nbt);
}