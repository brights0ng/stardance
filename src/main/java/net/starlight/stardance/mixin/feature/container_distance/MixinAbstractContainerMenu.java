package net.starlight.stardance.mixin.feature.container_distance;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Container distance validation for GridSpace blocks.
 * Enables crafting tables, chests, and other containers on moving grids.
 * Matches VS2's MixinScreenHandler exactly.
 */
@Mixin(AbstractContainerMenu.class)
public class MixinAbstractContainerMenu {

    /**
     * Intercepts container distance checks to include grids.
     * This uses VS2's exact targeting approach with wildcard method matching.
     */
    @Redirect(
        method = "*",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;distanceToSqr(DDD)D"
        ),
        require = 0
    )
    private static double includeGridsInDistanceCheck(
        final Player player, final double x, final double y, final double z) {
        
        try {
            // Check if container position is in GridSpace
            BlockPos containerPos = BlockPos.containing(x, y, z);
            LocalGrid grid = GridSpaceManager.getGridAtPosition(containerPos);
            
            if (grid != null) {
                // Convert GridSpace coordinates to visual world coordinates
                Vec3 gridSpacePos = new Vec3(x, y, z);
                Vec3 visualWorldPos = grid.gridSpaceToWorldSpace(gridSpacePos);
                
                // Use player's eye position for accurate distance (matches VS2)
                Vec3 playerEyePos = player.getEyePosition();
                return playerEyePos.distanceToSqr(visualWorldPos);
            } else {
                // Regular world container - use vanilla calculation
                return player.distanceToSqr(x, y, z);
            }
            
        } catch (Exception e) {
            SLogger.log("MixinAbstractContainerMenu", "Error in container distance check: " + e.getMessage());
            // Fallback to vanilla distance
            return player.distanceToSqr(x, y, z);
        }
    }
}