package net.starlight.stardance.core;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Interface for block entities that exist on grids.
 */
public interface GridBlockEntity {
    /**
     * Called every server tick to update the block entity.
     */
    void tick(World world, BlockPos pos, BlockState state);
    
    /**
     * Writes the block entity data to NBT for persistence.
     */
    NbtCompound writeNbt(NbtCompound nbt);
    
    /**
     * Reads the block entity data from NBT.
     */
    void readNbt(NbtCompound nbt);
}