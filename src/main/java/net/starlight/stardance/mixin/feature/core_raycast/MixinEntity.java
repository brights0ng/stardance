package net.starlight.stardance.mixin.feature.core_raycast;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.gridspace.utils.GridSpaceRaycastUtils;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Core raycast mixin - the foundation of all Stardance block interactions.
 * 
 * Intercepts Entity.pick() raycasts to include GridSpace blocks in addition to world blocks.
 * This mixin is absolutely critical - without it, players cannot interact with grid blocks.
 * 
 * Based on VS2's MixinEntity pattern but adapted for GridSpace architecture.
 */
@Mixin(Entity.class)
public class MixinEntity {
    
    /**
     * Replaces vanilla Level.clip() calls with grid-aware raycasting.
     * 
     * This is the core foundation of Stardance's interaction system. Every time a player
     * looks at blocks, breaks blocks, places blocks, or interacts with blocks, this method
     * is called to determine what they're targeting.
     * 
     * The method performs both vanilla world raycasting AND GridSpace raycasting,
     * then returns whichever hit is closer to the ray origin.
     * 
     * @param receiver The Level instance (world)
     * @param ctx The ClipContext containing ray start/end points and options
     * @return BlockHitResult containing the closest hit (world or grid block)
     */
    @Redirect(
            method = "pick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"
            )
    )
    public BlockHitResult addGridsToRaycast(final Level receiver, final ClipContext ctx) {
        try {
            SLogger.log("MixinEntity", "Intercepted raycast from " + formatVec3(ctx.getFrom()) + " to " + formatVec3(ctx.getTo()));

            // Use GridSpaceRaycastUtils to perform dual raycasting (world + grids)
            BlockHitResult result = GridSpaceRaycastUtils.clipIncludeGrids(receiver, ctx);

            // Log the result for debugging
            SLogger.log("MixinEntity", "Raycast result: " + result.getType() + " at " + result.getBlockPos());

            return result;

        } catch (Exception e) {
            // Graceful degradation: if grid raycasting fails, fall back to vanilla
            SLogger.log("MixinEntity", "Grid raycast failed, falling back to vanilla: " + e.getMessage());
            e.printStackTrace();
            return receiver.clip(ctx);
        }
    }

    // Helper method
    private String formatVec3(Vec3 vec) {
        return String.format("(%.2f, %.2f, %.2f)", vec.x, vec.y, vec.z);
    }
}