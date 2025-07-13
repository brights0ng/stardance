package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceBlockManager;
import net.starlight.stardance.utils.GridSpaceCoordinateUtils;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * VS2-style block breaking mixin.
 * Intercepts Block.onBreak() to handle GridSpace coordinate transformation.
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManagerBreaking {

    @Shadow @Final protected ServerPlayerEntity player;
    @Shadow protected ServerWorld world;

    static {
        SLogger.log("MixinServerPlayerInteractionManagerBreaking","Registered breaking mixin!");
    }

    /**
     * Intercept Block.onBreak() to handle GridSpace blocks with adjacent checking.
     */
    @WrapOperation(
            method = "tryBreakBlock",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/block/Block;onBreak(Lnet/minecraft/world/World;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;Lnet/minecraft/entity/player/PlayerEntity;)V")
    )
    private void interceptBlockBreaking(Block block, World world, BlockPos pos, BlockState state, PlayerEntity player, Operation<Void> original) {
        try {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking",
                    "Block breaking at: " + pos + " by " + player.getName().getString());

            // 1. Use enhanced coordinate detection that handles raycast edge cases
            Optional<GridSpaceCoordinateUtils.GridSpaceBlockResult> gridResult =
                    GridSpaceCoordinateUtils.findActualGridBlock(pos, world);

            if (gridResult.isPresent()) {
                handleGridSpaceBlockBreaking(gridResult.get(), pos, state, block, original, world, player);
            } else {
                // Regular world block breaking
                SLogger.log("MixinServerPlayerInteractionManagerBreaking", "Regular world block breaking");
                original.call(block, world, pos, state, player);
            }

        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking",
                    "Error in block breaking: " + e.getMessage());
            e.printStackTrace();
            // Fallback to original behavior
            original.call(block, world, pos, state, player);
        }
    }

    /**
     * Handle block breaking in GridSpace (updated to use GridSpaceBlockResult).
     */
    private void handleGridSpaceBlockBreaking(GridSpaceCoordinateUtils.GridSpaceBlockResult gridResult,
                                              BlockPos originalPos,
                                              BlockState state,
                                              Block block,
                                              Operation<Void> original,
                                              World world,
                                              PlayerEntity player) {

        LocalGrid grid = gridResult.grid;
        BlockPos gridLocalPos = gridResult.gridLocalPos;
        BlockPos actualGridSpacePos = gridResult.actualGridSpacePos;

        SLogger.log("MixinServerPlayerInteractionManagerBreaking",
                String.format("Breaking GridSpace block: requested=%s, actual=%s, grid-local=%s",
                        originalPos, actualGridSpacePos, gridLocalPos));

        // Rest of your existing logic, but use actualGridSpacePos instead of the original pos

        // 1. Validate grid state
        if (grid.isDestroyed()) {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking", "Grid is destroyed - skipping break");
            return;
        }

        // 2. Validate player permissions and distance (use actualGridSpacePos)
        if (!canPlayerBreakGridBlock(grid, gridLocalPos, actualGridSpacePos, player)) {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking", "Player cannot break this grid block");
            return;
        }

        // 3. Get GridSpace block manager
        GridSpaceBlockManager blockManager = grid.getGridSpaceBlockManager();
        if (blockManager == null) {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking", "No GridSpace block manager - using original break");
            original.call(block, world, actualGridSpacePos, state, player);
            return;
        }

        // 4. Execute the original Block.onBreak() with the ACTUAL GridSpace coordinates
        original.call(block, world, actualGridSpacePos, state, player);

        // 5. Remove from GridSpace storage (use actualGridSpacePos)
        boolean removedFromGridSpace = blockManager.removeBlock(actualGridSpacePos);

        // 6. Remove from grid's local storage
        boolean removedFromGrid = grid.removeBlock(gridLocalPos);

        if (removedFromGridSpace && removedFromGrid) {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking",
                    "Successfully removed block from both GridSpace and grid storage");

            // 7. Mark grid dirty for physics rebuild
            grid.markDirty();

            SLogger.log("MixinServerPlayerInteractionManagerBreaking",
                    "GridSpace block breaking completed successfully");
        } else {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking",
                    String.format("Block removal issue - GridSpace: %b, Grid: %b",
                            removedFromGridSpace, removedFromGrid));
        }
    }

    /**
     * Validate player permissions for breaking grid blocks.
     * Uses world coordinates for distance calculation (VS2 pattern).
     */
    private boolean canPlayerBreakGridBlock(LocalGrid grid, BlockPos gridLocalPos, BlockPos gridSpacePos, PlayerEntity player) {
        try {
            // 1. Distance validation using world coordinates
            Vec3d blockWorldPos = TransformationAPI.getWorldCoordinates(
                world, gridSpacePos, new Vec3d(0.5, 0.5, 0.5));
            
            Vec3d playerPos = player.getPos();
            double distanceSquared = TransformationAPI.squaredDistanceBetweenInclGrids(world, playerPos, blockWorldPos);
            
            // Standard interaction distance (8 blocks = 64 squared)
            if (distanceSquared > 64.0) {
                SLogger.log("MixinServerPlayerInteractionManagerBreaking", 
                    String.format("Block too far: %.2f blocks", Math.sqrt(distanceSquared)));
                return false;
            }
            
            // 2. Block existence validation
            if (!grid.hasBlock(gridLocalPos)) {
                SLogger.log("MixinServerPlayerInteractionManagerBreaking", 
                    "Block doesn't exist in grid: " + gridLocalPos);
                return false;
            }
            
            // 3. Additional permission checks could go here
            // (e.g., protection plugins, creative mode checks, etc.)
            
            return true;
            
        } catch (Exception e) {
            SLogger.log("MixinServerPlayerInteractionManagerBreaking", 
                "Error validating break permissions: " + e.getMessage());
            return false;
        }
    }
}