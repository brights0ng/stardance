package net.starlight.stardance.mixin.feature.block_state_access;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Intercepts block placement calls to handle GridSpace blocks.
 * Matches VS2's approach for block state modification.
 */
@Mixin(Level.class)
public class MixinLevel {

    /**
     * Intercepts setBlockAndUpdate to handle grid block placement.
     * This matches VS2's pattern for block state modification.
     */
    @WrapOperation(
            method = "setBlockAndUpdate",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")
    )
    private boolean setBlockIncludeGrids(Level instance, BlockPos pos, BlockState state, int flags, Operation<Boolean> original) {
        try {
            // Check if this position corresponds to a grid block
            LocalGrid grid = GridSpaceManager.getGridAtPosition(pos);

            if (grid != null) {
                // This is a grid position - handle in grid space
                Vec3 worldPos = Vec3.atCenterOf(pos);
                Vec3 gridSpacePos = grid.worldToGridSpace(worldPos);
                BlockPos gridSpaceBlockPos = BlockPos.containing(gridSpacePos);

                boolean success;
                if (state.isAir()) {
                    // Removing block
                    success = grid.removeBlock(gridSpaceBlockPos);
                } else {
                    // Placing block - create LocalBlock
                    LocalBlock localBlock = new LocalBlock(gridSpaceBlockPos, state);
                    success = grid.addBlock(localBlock);
                }

                if (success) {
                    // Notify clients of the change using world coordinates
                    if (instance instanceof ServerLevel serverLevel) {
                        serverLevel.sendBlockUpdated(pos, state, state, flags);
                    }

                    SLogger.log("MixinLevel",
                            String.format("Set grid block: world=%s → grid=%s, state=%s",
                                    pos.toShortString(),
                                    gridSpaceBlockPos.toShortString(),
                                    state.getBlock().getDescriptionId()));
                }

                return success;

            } else {
                // Regular world block - use vanilla behavior
                return original.call(instance, pos, state, flags);
            }

        } catch (Exception e) {
            SLogger.log("MixinLevel", "Error in setBlock: " + e.getMessage());
            e.printStackTrace();

            // Fallback to vanilla behavior
            return original.call(instance, pos, state, flags);
        }
    }

    /**
     * Intercepts removeBlock to handle grid block removal.
     */
    @WrapOperation(
            method = "removeBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z")
    )
    private boolean removeBlockIncludeGrids(Level instance, BlockPos pos, BlockState airState, int flags, Operation<Boolean> original) {
        // Use the same logic as setBlockIncludeGrids since removing is just setting to air
        return setBlockIncludeGrids(instance, pos, airState, flags, original);
    }
}