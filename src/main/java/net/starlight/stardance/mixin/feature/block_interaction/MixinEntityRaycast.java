package net.starlight.stardance.mixin.feature.block_interaction;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.entity.Entity;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.debug.InteractionDebugManager;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;
import net.starlight.stardance.utils.TransformationAPI;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

/**
 * CORE INTERACTION MIXIN: Intercepts Entity.raycast to transform world coordinates to GridSpace.
 * This is the foundation of our VS2-style interaction system.
 * 
 * When a player looks at blocks on a grid, this mixin:
 * 1. Performs normal world raycasting
 * 2. Checks if the hit position intersects with any grid
 * 3. Transforms the HitResult to use GridSpace coordinates
 * 4. Returns GridSpace HitResult for vanilla interaction handling
 */
@Mixin(Entity.class)
public class MixinEntityRaycast implements ILoggingControl {

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return InteractionDebugManager.isRaycastDebuggingEnabled();
    }

    /**
     * CRITICAL MIXIN: Intercepts world raycasting to handle grid block targeting.
     * This is called by Entity.raycast() which is used for all block/entity targeting.
     */
    @WrapOperation(
        method = "raycast",
        at = @At(value = "INVOKE", 
                 target = "Lnet/minecraft/world/World;raycast(Lnet/minecraft/world/RaycastContext;)Lnet/minecraft/util/hit/BlockHitResult;")
    )
    private BlockHitResult interceptWorldRaycast(World world, RaycastContext context, Operation<BlockHitResult> original) {
        // Perform the original world raycast first
        BlockHitResult worldResult = original.call(world, context);

        // Skip transformation if we missed entirely
        if (worldResult.getType() == HitResult.Type.MISS) {
            return worldResult;
        }

        // Skip if we're not in a server world (client prediction will be handled separately)
        if (world.isClient) {
            return worldResult;
        }

        try {
            // Check if the hit position is on a grid using our TransformationAPI
            Vec3d worldHitPos = worldResult.getPos();
            Optional<TransformationAPI.GridSpaceTransformResult> gridTransform = 
                TransformationAPI.getInstance().worldToGridSpace(worldHitPos, world);

            if (gridTransform.isPresent()) {
                // We hit a grid! Transform the HitResult to use GridSpace coordinates
                TransformationAPI.GridSpaceTransformResult transform = gridTransform.get();
                
                // Create new GridSpace HitResult
                BlockHitResult gridSpaceResult = createGridSpaceHitResult(worldResult, transform);

                if (stardance$isConsoleLoggingEnabled()) {
                    SLogger.log(this, String.format(
                        "Raycast HIT GRID: World %s → GridSpace %s (Grid: %s)", 
                        worldHitPos, transform.gridSpacePos, transform.grid.getGridId()
                    ));
                }

                return gridSpaceResult;
            } else {
                // No grid hit, return vanilla result
                if (stardance$isConsoleLoggingEnabled()) {
                    SLogger.log(this, "Raycast hit world block at " + worldHitPos + " (no grid)");
                }
                return worldResult;
            }

        } catch (Exception e) {
            // Fallback to vanilla behavior on any error
            SLogger.log(this, "Error in raycast transformation: " + e.getMessage());
            e.printStackTrace();
            return worldResult;
        }
    }

    /**
     * Creates a new BlockHitResult with GridSpace coordinates while preserving all other properties.
     */
    private BlockHitResult createGridSpaceHitResult(BlockHitResult originalResult, 
                                                    TransformationAPI.GridSpaceTransformResult transform) {
        
        // Create BlockHitResult using GridSpace coordinates
        return new BlockHitResult(
            transform.gridSpaceVec,           // Hit position in GridSpace
            originalResult.getSide(),         // Keep original face direction
            transform.gridSpacePos,           // Block position in GridSpace  
            originalResult.isInsideBlock()    // Keep original inside block state
        );
    }
}