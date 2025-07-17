package net.starlight.stardance.mixin.feature.drop_spawning;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.gridspace.GridSpaceManager;
import net.starlight.stardance.utils.SLogger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Intercepts block drop spawning to handle grid block drops correctly.
 * Matches VS2's exact approach for item entity coordinate transformation.
 */
@Mixin(Block.class)
public class MixinBlock {

    /**
     * Intercepts ItemEntity creation for block drops to spawn them in world coordinates for grid blocks.
     * This matches VS2's exact @WrapOperation pattern for drop spawning.
     */
    @WrapOperation(
        method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(value = "NEW", 
            target = "(Lnet/minecraft/world/level/Level;DDDLnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/entity/item/ItemEntity;")
    )
    private static ItemEntity adjustDropPosition(Level level, double x, double y, double z, 
                                                ItemStack stack, Operation<ItemEntity> original) {
        try {
            BlockPos gridPos = BlockPos.containing(x, y, z);
            LocalGrid grid = GridSpaceManager.getGridAtPosition(gridPos);
            
            if (grid != null) {
                // Convert grid coordinates to world coordinates for drop spawning
                Vec3 gridSpaceVec = new Vec3(x, y, z);
                Vec3 worldVec = grid.gridSpaceToWorldSpace(gridSpaceVec);
                
                SLogger.log("MixinBlock", 
                    String.format("Adjusting drop position: grid=(%.1f,%.1f,%.1f) → world=(%.1f,%.1f,%.1f)", 
                        x, y, z, worldVec.x, worldVec.y, worldVec.z));
                
                return original.call(level, worldVec.x, worldVec.y, worldVec.z, stack);
            }
        } catch (Exception e) {
            SLogger.log("MixinBlock", "Error adjusting drop position: " + e.getMessage());
        }
        
        // Fallback to original coordinates
        return original.call(level, x, y, z, stack);
    }

    /**
     * Intercepts the more general popResource method that takes a BlockPos.
     */
    @WrapOperation(
        method = "popResource(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/item/ItemStack;)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z")
    )
    private static boolean adjustDropSpawning(Level level, net.minecraft.world.entity.Entity entity, Operation<Boolean> original) {
        try {
            if (entity instanceof ItemEntity itemEntity) {
                Vec3 entityPos = itemEntity.position();
                BlockPos gridPos = BlockPos.containing(entityPos);
                LocalGrid grid = GridSpaceManager.getGridAtPosition(gridPos);
                
                if (grid != null) {
                    // Convert grid coordinates to world coordinates
                    Vec3 worldPos = grid.gridSpaceToWorldSpace(entityPos);
                    
                    // Update the item entity's position to world coordinates
                    itemEntity.setPos(worldPos.x, worldPos.y, worldPos.z);
                    
                    SLogger.log("MixinBlock", 
                        String.format("Adjusted item entity position: grid=%s → world=%s", 
                            String.format("(%.1f,%.1f,%.1f)", entityPos.x, entityPos.y, entityPos.z),
                            String.format("(%.1f,%.1f,%.1f)", worldPos.x, worldPos.y, worldPos.z)));
                }
            }
        } catch (Exception e) {
            SLogger.log("MixinBlock", "Error adjusting item entity spawn: " + e.getMessage());
        }
        
        // Spawn the entity (potentially with adjusted position)
        return original.call(level, entity);
    }
}