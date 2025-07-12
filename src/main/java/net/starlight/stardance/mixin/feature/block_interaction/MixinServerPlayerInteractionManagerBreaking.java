package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.gridspace.GridSpaceRegion;
import net.starlight.stardance.physics.PhysicsEngine;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * VS2-Style Block Breaking: Intercepts block breaking to handle GridSpace blocks.
 * When a player breaks a block on a grid, this mixin:
 * 1. Detects if the target position is in GridSpace
 * 2. Finds the corresponding LocalGrid
 * 3. Removes the block from both GridSpace and local storage
 * 4. Triggers physics rebuild
 */
@Mixin(ServerPlayerInteractionManager.class)
public class MixinServerPlayerInteractionManagerBreaking implements ILoggingControl {

    // NEW: Intercept the initial breaking attempt to see what coordinates server receives
    @Inject(method = "tryBreakBlock", at = @At("HEAD"))
    private void logBreakingAttempt(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        SLogger.log("BlockBreaking", "=== SERVER BREAKING ATTEMPT ===");
        SLogger.log("BlockBreaking", "Server received position: " + pos);
        SLogger.log("BlockBreaking", "Is GridSpace: " + (pos.getX() >= 20_000_000 || pos.getZ() >= 20_000_000));

        // Let's also test server-side raycast
        ServerPlayerEntity player = this.player; // You have access to this
        if (player != null) {
            HitResult serverRaycast = player.raycast(10.0, 0.0f, false);
            SLogger.log("BlockBreaking", "Server raycast result: " + serverRaycast.getType());
            if (serverRaycast instanceof BlockHitResult) {
                BlockPos serverPos = ((BlockHitResult) serverRaycast).getBlockPos();
                SLogger.log("BlockBreaking", "Server raycast position: " + serverPos);
                SLogger.log("BlockBreaking", "Server raycast is GridSpace: " + (serverPos.getX() >= 20_000_000));
            }
        }
    }

    @Shadow
    public ServerPlayerEntity player;

    @Shadow
    public ServerWorld world;

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true; // Enable for debugging block breaking
    }

    static {
        SLogger.log("BlockBreaking","BB initialized!!");
    }

    /**
     * CORE MIXIN: Intercepts block state retrieval during breaking.
     * This is called when the server checks what block is being broken.
     */
    @WrapOperation(
        method = "tryBreakBlock",
        at = @At(value = "INVOKE", 
                 target = "Lnet/minecraft/server/world/ServerWorld;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;")
    )
    private BlockState interceptBlockStateQuery(ServerWorld world, BlockPos pos, Operation<BlockState> original) {
        SLogger.log("BlockBreaking", "=== BLOCK BREAKING ATTEMPT ===");
        SLogger.log("BlockBreaking", "Position: " + pos);
        SLogger.log("BlockBreaking", "Is GridSpace coordinate: " + isGridSpaceCoordinate(pos));

        try {
            // Check if this position is in GridSpace
            Optional<LocalGrid> gridOpt = findGridForGridSpacePosition(pos, world);

            if (gridOpt.isPresent()) {
                LocalGrid grid = gridOpt.get();
                BlockPos gridLocalPos = grid.gridSpaceToGridLocal(pos);
                BlockState blockState = grid.getBlock(gridLocalPos);

                SLogger.log("BlockBreaking", "✓ Found grid block: " + gridLocalPos + " = " +
                        (blockState != null ? blockState.getBlock().getName().getString() : "null"));

                return blockState != null ? blockState : net.minecraft.block.Blocks.AIR.getDefaultState();
            } else {
                SLogger.log("BlockBreaking", "Not a grid block, using vanilla");
            }

        } catch (Exception e) {
            SLogger.log("BlockBreaking", "Error: " + e.getMessage());
        }

        return original.call(world, pos);
    }

    private boolean isGridSpaceCoordinate(BlockPos pos) {
        return pos.getX() >= 20_000_000 || pos.getZ() >= 20_000_000;
    }

    /**
     * CORE MIXIN: Intercepts actual block removal during breaking.
     * This is called when the server actually removes the block.
     */
    @WrapOperation(
        method = "tryBreakBlock",
        at = @At(value = "INVOKE", 
                 target = "Lnet/minecraft/server/world/ServerWorld;removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z")
    )
    private boolean interceptBlockRemoval(ServerWorld world, BlockPos pos, boolean move, Operation<Boolean> original) {
        try {
            // Check if this position is in GridSpace
            Optional<LocalGrid> gridOpt = findGridForGridSpacePosition(pos, world);
            
            if (gridOpt.isPresent()) {
                LocalGrid grid = gridOpt.get();
                
                // Convert GridSpace → grid-local coordinates
                BlockPos gridLocalPos = grid.gridSpaceToGridLocal(pos);
                
                SLogger.log(this, String.format(
                    "Breaking block: GridSpace %s → grid-local %s on grid %s", 
                    pos, gridLocalPos, grid.getGridId()
                ));
                
                // Remove block from both GridSpace and local storage
                grid.removeBlock(gridLocalPos);
                
                // Success - block removed from GridSpace
                SLogger.log(this, "Successfully removed block from grid " + grid.getGridId());
                return true;
            }
            
        } catch (Exception e) {
            SLogger.log(this, "Error during block removal: " + e.getMessage());
            e.printStackTrace();
            return false; // Failed to remove
        }
        
        // Fallback to vanilla behavior for world blocks
        return original.call(world, pos, move);
    }

    /**
     * Finds the LocalGrid that owns a specific GridSpace position.
     * Uses your existing GridSpaceManager infrastructure.
     */
    private Optional<LocalGrid> findGridForGridSpacePosition(BlockPos gridSpacePos, World world) {
        try {
            // Get GridSpace manager for this world
            GridSpaceManager gridSpaceManager = engineManager.getGridSpaceManager((ServerWorld) world);
            if (gridSpaceManager == null) {
                return Optional.empty();
            }
            
            // Find which region contains this position
            GridSpaceRegion region = gridSpaceManager.getRegionContaining(gridSpacePos);
            if (region == null) {
                return Optional.empty();
            }
            
            // Get the grid ID from the region
            UUID gridId = region.getGridId();
            
            // Find the LocalGrid with this ID
            PhysicsEngine engine = engineManager.getEngine((ServerWorld) world);
            if (engine == null) {
                return Optional.empty();
            }
            
            // Search through all grids to find the matching one
            for (LocalGrid grid : engine.getGrids()) {
                if (grid.getGridId().equals(gridId)) {
                    return Optional.of(grid);
                }
            }
            
            SLogger.log(this, "Found region " + region.getRegionId() + " but no matching grid for ID " + gridId);
            return Optional.empty();
            
        } catch (Exception e) {
            SLogger.log(this, "Error finding grid for GridSpace position " + gridSpacePos + ": " + e.getMessage());
            return Optional.empty();
        }
    }

}