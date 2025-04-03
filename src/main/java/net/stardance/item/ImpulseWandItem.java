package net.stardance.item;

import com.bulletphysics.dynamics.RigidBody;
import com.google.common.collect.Multimap;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

import static net.stardance.Stardance.engineManager;

/**
 * A wand item that applies impulse forces to grids.
 * Right-click to pull the nearest grid toward the clicked point.
 */
public class ImpulseWandItem extends Item implements ILoggingControl {
    private static final float DEFAULT_IMPULSE_STRENGTH = 3000.0f;
    private static final float MAX_IMPULSE_DISTANCE = 20.0f;

    public ImpulseWandItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        if (world.isClient) {
            return TypedActionResult.pass(player.getStackInHand(hand));
        }

        // Get the item stack
        ItemStack stack = player.getStackInHand(hand);

        // Perform a raycast to find what the player is looking at
        HitResult hitResult = player.raycast(MAX_IMPULSE_DISTANCE, 0.0f, false);

        // Only proceed if we hit a block
        if (hitResult.getType() != HitResult.Type.BLOCK) {
            player.sendMessage(Text.literal("No block in range. Point at a block and try again."), true);
            return TypedActionResult.fail(stack);
        }

        BlockHitResult blockHit = (BlockHitResult) hitResult;
        Vec3d hitPos = blockHit.getPos();

        // Find the nearest physics grid
        Optional<LocalGrid> nearestGrid = findNearestGrid(world, hitPos);

        if (nearestGrid.isPresent()) {
            LocalGrid grid = nearestGrid.get();

            // Apply an impulse toward the hit position
            applyImpulseToward(grid, hitPos, player.isSneaking() ? DEFAULT_IMPULSE_STRENGTH * 3 : DEFAULT_IMPULSE_STRENGTH);

            // Notify the player
            player.sendMessage(Text.literal("Applied impulse to grid!"), true);
            return TypedActionResult.success(stack);
        } else {
            player.sendMessage(Text.literal("No physics grids found nearby."), true);
            return TypedActionResult.fail(stack);
        }
    }

    /**
     * Finds the nearest LocalGrid to a given position.
     */
    private Optional<LocalGrid> findNearestGrid(World world, Vec3d position) {
        if (!(world instanceof ServerWorld)) {
            return Optional.empty();
        }

        ServerWorld serverWorld = (ServerWorld) world;
        PhysicsEngine engine = engineManager.getEngine(serverWorld);

        if (engine == null) {
            SLogger.log(this, "No physics engine found for world");
            return Optional.empty();
        }

        Set<LocalGrid> grids = engine.getGrids();

        // Find the nearest grid by comparing distances to their centers
        return grids.stream()
                .min(Comparator.comparingDouble(grid -> {
                    RigidBody body = grid.getRigidBody();
                    if (body == null) return Double.MAX_VALUE;

                    Vector3f center = new Vector3f();
                    body.getCenterOfMassPosition(center);

                    double dx = center.x - position.x;
                    double dy = center.y - position.y;
                    double dz = center.z - position.z;

                    return dx*dx + dy*dy + dz*dz;
                }));
    }

    /**
     * Applies an impulse to a grid, pulling it toward the specified position.
     */
    private void applyImpulseToward(LocalGrid grid, Vec3d targetPos, float strength) {
        RigidBody body = grid.getRigidBody();
        if (body == null) return;

        // Get the grid's current position
        Vector3f currentPos = new Vector3f();
        body.getCenterOfMassPosition(currentPos);

        // Calculate direction vector from grid to target
        Vector3f impulseDir = new Vector3f(
                (float)(targetPos.x - currentPos.x),
                (float)(targetPos.y - currentPos.y),
                (float)(targetPos.z - currentPos.z)
        );

        // Normalize and scale by strength
        float length = impulseDir.length();
        if (length > 0.001f) {
            impulseDir.scale(strength / length);

            // Apply central impulse to the rigid body
            body.applyCentralImpulse(impulseDir);

            // Wake up the body to ensure it responds to the impulse
            body.activate(true);

            SLogger.log(this, "Applied impulse: " + impulseDir + " to grid at " + currentPos);
        }
    }

    @Override
    public Multimap<EntityAttribute, EntityAttributeModifier> getAttributeModifiers(ItemStack stack, EquipmentSlot slot) {
        return super.getAttributeModifiers(stack, slot);
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }
}