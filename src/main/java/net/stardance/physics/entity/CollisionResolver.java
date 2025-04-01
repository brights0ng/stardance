package net.stardance.physics.entity;

import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.stardance.core.LocalGrid;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;

import javax.vecmath.Vector3f;
import java.util.*;

/**
 * Handles collision resolution for entities.
 * Implements realistic sliding mechanics and ground detection.
 */
public class CollisionResolver implements ILoggingControl {

    // Constants for physics behavior adjustment
    private static final float PENETRATION_CORRECTION_FACTOR = -1.0f;  // How aggressively to correct penetration
    private static final float GROUND_NORMAL_THRESHOLD = 0.7071f;     // cos(45°) - normals with y above this are ground
    private static final float MIN_GROUND_CONTACT_DEPTH = 0.001f;     // Minimum penetration to count as ground
    private static final float EPSILON = 0.0001f;                     // Small value for float comparisons

    /**
     * Applies gentle position correction to prevent entity penetration into grids.
     * Uses the minimum displacement needed to resolve penetration while ensuring
     * the entity doesn't clip into world blocks.
     *
     * @param entity Entity to adjust
     * @param contacts List of contacts for the entity
     */
    public void applyGentlePositionCorrection(Entity entity, List<Contact> contacts) {
        if (contacts.isEmpty()) {
            return;
        }

        // Find grid contacts only
        List<Contact> gridContacts = new ArrayList<>();
        for (Contact contact : contacts) {
            if (contact.isGridContact()) {
                gridContacts.add(contact);
            }
        }

        if (gridContacts.isEmpty()) {
            return;
        }

        // Check if any contact indicates the entity is standing on a grid
        boolean standingOnGrid = false;
        for (Contact contact : gridContacts) {
            Vector3f normal = contact.getContactNormal();
            // Normal points up significantly
            if (normal.y > 0.7071f) {
                standingOnGrid = true;
                break;
            }
        }

        // Calculate correction vector
        Vector3f correctionVec = new Vector3f(0, 0, 0);

        for (Contact contact : gridContacts) {
            // Skip contacts with insufficient penetration
            if (contact.getPenetrationDepth() < MIN_GROUND_CONTACT_DEPTH) {
                continue;
            }

            // Get the contact normal and penetration depth
            Vector3f normal = contact.getContactNormal();
            float depth = contact.getPenetrationDepth();

            // If standing on grid, reduce horizontal correction significantly to prevent ejection
            if (standingOnGrid && normal.y < 0.7071f) {
                depth *= 1f; // Reduce horizontal correction when standing on grid
            }

            // Scale normal by depth to get this contact's correction
            Vector3f contactCorrection = new Vector3f(normal);
            contactCorrection.scale(depth);

            // Add to total correction
            correctionVec.add(contactCorrection);
        }

        // Scale correction to avoid overshooting
        correctionVec.scale(PENETRATION_CORRECTION_FACTOR);

//        // Eliminate any downward movement component
//        if (correctionVec.y < 0) {
//            correctionVec.y = 0;
//        }

        // Apply correction if significant
        if (correctionVec.lengthSquared() > EPSILON) {
            // Convert to Vec3d
            Vec3d correction = new Vec3d(
                    correctionVec.x,
                    correctionVec.y,
                    correctionVec.z
            );

            // Check if the correction would push the entity into world blocks
            if (!isMovementSafe(entity, correction)) {
                // If unsafe, try horizontal movement only
                Vec3d horizontalCorrection = new Vec3d(correction.x, 0, correction.z);
                if (!isMovementSafe(entity, horizontalCorrection)) {
                    // If still unsafe, try minimal corrections along each axis
                    correction = findSafeCorrection(entity, correction);
                } else {
                    correction = horizontalCorrection;
                }
            }

            // Apply position correction if we found a safe movement
            if (correction.lengthSquared() > EPSILON) {
                entity.setPos(
                        entity.getX() + correction.x,
                        entity.getY() + correction.y,
                        entity.getZ() + correction.z
                );

                SLogger.log(this, "Applied position correction: " + correction + " to entity: " + entity.getUuid());
            }
        }
    }

    /**
     * Checks if a proposed movement would result in the entity colliding with world blocks.
     *
     * @param entity The entity to check
     * @param movement The proposed movement vector
     * @return True if the movement is safe (no world block collisions), false otherwise
     */
    private boolean isMovementSafe(Entity entity, Vec3d movement) {
        // If no movement, it's safe
        if (movement.lengthSquared() < EPSILON) {
            return true;
        }

        // Get the entity's current bounding box
        Box entityBox = entity.getBoundingBox();

        // Calculate the new bounding box after movement
        Box newBox = entityBox.offset(movement);

        // Check if the new box collides with any world blocks
        return !hasWorldBlockCollisions(entity.getWorld(), newBox);
    }

    /**
     * Checks if a bounding box collides with any world blocks.
     *
     * @param world The world to check in
     * @param box The bounding box to check
     * @return True if there are collisions, false otherwise
     */
    private boolean hasWorldBlockCollisions(net.minecraft.world.World world, Box box) {
        // Get the block positions that the box could intersect with
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = (int) Math.ceil(box.maxX);
        int maxY = (int) Math.ceil(box.maxY);
        int maxZ = (int) Math.ceil(box.maxZ);

        // Check each potential block
        for (int x = minX; x < maxX; x++) {
            for (int y = minY; y < maxY; y++) {
                for (int z = minZ; z < maxZ; z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockState blockState = world.getBlockState(pos);

                    // Skip air blocks
                    if (blockState.isAir()) {
                        continue;
                    }

                    // Get the block's collision shape
                    VoxelShape shape = blockState.getCollisionShape(world, pos);

                    // FIX: Skip empty shapes
                    if (shape.isEmpty()) {
                        continue;
                    }

                    // Get the block's bounding box and check for intersection
                    Box blockBox = shape.getBoundingBox().offset(pos);

                    // Check for intersection
                    if (blockBox.intersects(box)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Tries to find a safe correction vector when the original correction
     * would push the entity into world blocks.
     *
     * @param entity The entity to correct
     * @param originalCorrection The original correction vector
     * @return A safe correction vector, or zero vector if no safe correction is found
     */
    private Vec3d findSafeCorrection(Entity entity, Vec3d originalCorrection) {
        // Try each component separately
        Vec3d xCorrection = new Vec3d(originalCorrection.x, 0, 0);
        Vec3d yCorrection = new Vec3d(0, originalCorrection.y, 0);
        Vec3d zCorrection = new Vec3d(0, 0, originalCorrection.z);

        // Check each component
        boolean xSafe = isMovementSafe(entity, xCorrection);
        boolean ySafe = isMovementSafe(entity, yCorrection);
        boolean zSafe = isMovementSafe(entity, zCorrection);

        // Build the safe correction
        double safeX = xSafe ? originalCorrection.x : 0;
        double safeY = ySafe ? originalCorrection.y : 0;
        double safeZ = zSafe ? originalCorrection.z : 0;

        // If y correction would push down, zero it out
        if (safeY < 0) {
            safeY = 0;
        }

        return new Vec3d(safeX, safeY, safeZ);
    }

    /**
     * Updates the entity's on-ground state based on contacts.
     * An entity is on ground if it has a contact with a normal pointing upward.
     *
     * @param entity Entity to update
     * @param contacts List of contacts for the entity
     */
    public void updateOnGroundState(Entity entity, List<Contact> contacts) {
        boolean wasOnGround = entity.isOnGround();
        boolean isOnGround = false;

        for (Contact contact : contacts) {
            Vector3f normal = contact.getContactNormal();

            // Upward-pointing normal means we're on ground
            // The threshold 0.7071 is approximately cos(45°)
            if (normal.y > GROUND_NORMAL_THRESHOLD && contact.getPenetrationDepth() > MIN_GROUND_CONTACT_DEPTH) {
                isOnGround = true;
                break;
            }
        }

        // Update entity's ground state
        if (isOnGround != wasOnGround) {
            entity.setOnGround(isOnGround);
            SLogger.log(this, "Updated entity " + entity.getUuid() + " onGround state to: " + isOnGround);
        }
    }

    /**
     * Modifies an entity's movement vector when colliding with a grid.
     * Implements sliding behavior when colliding with walls.
     *
     * @param entity Entity that is moving
     * @param originalMovement Original intended movement
     * @param contacts Contacts to resolve
     * @return Modified movement vector
     */
    public Vec3d resolveMovement(Entity entity, Vec3d originalMovement, List<Contact> contacts) {
        // If no movement or no contacts, nothing to resolve
        if (originalMovement.lengthSquared() < EPSILON || contacts.isEmpty()) {
            return originalMovement;
        }

        // Start with the original movement
        Vec3d resolvedMovement = originalMovement;

        // Sort contacts by penetration depth (deepest first)
        contacts.sort((a, b) -> Float.compare(b.getPenetrationDepth(), a.getPenetrationDepth()));

        // Process each contact
        for (Contact contact : contacts) {
            resolvedMovement = resolveContactMovement(resolvedMovement, contact);
        }

        return resolvedMovement;
    }

    /**
     * Resolves movement for a single contact by splitting into
     * parallel and perpendicular components.
     *
     * @param movement Movement to resolve
     * @param contact Contact to resolve against
     * @return Resolved movement
     */
    private Vec3d resolveContactMovement(Vec3d movement, Contact contact) {
        // Get the contact normal
        Vector3f normal = contact.getContactNormal();
        Vec3d normalVec = new Vec3d(normal.x, normal.y, normal.z);

        // Calculate dot product (component of movement into the surface)
        double dot = movement.dotProduct(normalVec);

        // If movement is not going into the surface, no need to resolve
        if (dot >= 0) {
            return movement;
        }

        // Calculate perpendicular component (into the surface)
        Vec3d perpendicular = normalVec.multiply(dot);

        // Calculate parallel component (along the surface)
        Vec3d parallel = movement.subtract(perpendicular);

        // Result is just the parallel component
        return parallel;
    }

    /**
     * Adjusts entity velocity based on contacts.
     * Makes entities slide along surfaces when colliding.
     *
     * @param entity Entity to adjust
     * @param contacts Contacts to consider
     */
    public void adjustEntityVelocity(Entity entity, List<Contact> contacts) {
        if (contacts.isEmpty()) {
            return;
        }

        // Get entity's current velocity
        Vec3d velocity = entity.getVelocity();

        // If no velocity, nothing to adjust
        if (velocity.lengthSquared() < EPSILON) {
            return;
        }

        // Sort contacts by penetration depth (deepest first)
        contacts.sort((a, b) -> Float.compare(b.getPenetrationDepth(), a.getPenetrationDepth()));

        // Start with the current velocity
        Vec3d adjustedVelocity = velocity;

        // Process each contact
        for (Contact contact : contacts) {
            adjustedVelocity = adjustVelocityForContact(adjustedVelocity, contact);
        }

        // Only update if the velocity changed
        if (!adjustedVelocity.equals(velocity)) {
            entity.setVelocity(adjustedVelocity);
            SLogger.log(this, "Adjusted entity velocity from " + velocity + " to " + adjustedVelocity);
        }
    }

    /**
     * Adjusts velocity for a single contact, taking into account
     * the relative velocity of the grid at the contact point.
     *
     * @param velocity Velocity to adjust
     * @param contact Contact to consider
     * @return Adjusted velocity
     */
    private Vec3d adjustVelocityForContact(Vec3d velocity, Contact contact) {
        // Get contact details
        Vector3f normal = contact.getContactNormal();
        Vec3d normalVec = new Vec3d(normal.x, normal.y, normal.z);

        // Get the grid's velocity at the contact point
        Vector3f gridVelocity = new Vector3f(0, 0, 0);
        if (contact.isGridContact()) {
            gridVelocity = contact.getGridVelocityAtContactPoint();
        }

        // Calculate relative velocity
        Vec3d relativeVelocity = velocity.subtract(
                new Vec3d(gridVelocity.x, gridVelocity.y, gridVelocity.z)
        );

        // Calculate dot product (component of velocity into the surface)
        double dot = relativeVelocity.dotProduct(normalVec);

        // If velocity is not going into the surface, no need to adjust
        if (dot >= 0) {
            return velocity;
        }

        // Calculate perpendicular component (into the surface)
        Vec3d perpendicular = normalVec.multiply(dot);

        // Calculate parallel component (along the surface)
        Vec3d parallel = relativeVelocity.subtract(perpendicular);

        // Result is parallel component plus grid velocity
        return parallel.add(new Vec3d(gridVelocity.x, gridVelocity.y, gridVelocity.z));
    }

    /**
     * Resolves all physics for an entity's contact with grids.
     * Applies position correction, velocity adjustment, and ground detection.
     *
     * @param entity Entity to resolve
     * @param movement Original movement vector
     * @param contacts Entity's contacts
     * @return Resolved movement vector
     */
    public Vec3d resolveCollisions(Entity entity, Vec3d movement, List<Contact> contacts) {
        // Filter for grid-only contacts
        List<Contact> gridContacts = new ArrayList<>();
        for (Contact contact : contacts) {
            if (contact.isGridContact()) {
                gridContacts.add(contact);
            }
        }

        if (gridContacts.isEmpty()) {
            return movement;
        }

        // Step 1: Apply gentle position correction to resolve penetration
        applyGentlePositionCorrection(entity, gridContacts);

        // Step 2: Resolve the movement vector for sliding
        Vec3d resolvedMovement = resolveMovement(entity, movement, gridContacts);

        // Step 3: Adjust entity velocity
        adjustEntityVelocity(entity, gridContacts);

        // Step 4: Update ground state
        updateOnGroundState(entity, gridContacts);

        return resolvedMovement;
    }

    /**
     * Groups contacts by the grid object they're with.
     * Useful for handling multiple contacts with the same grid.
     *
     * @param contacts List of contacts to group
     * @return Map of grids to lists of contacts
     */
    public Map<LocalGrid, List<Contact>> groupContactsByGrid(List<Contact> contacts) {
        Map<LocalGrid, List<Contact>> result = new HashMap<>();

        for (Contact contact : contacts) {
            if (contact.isGridContact()) {
                LocalGrid grid = contact.getGrid();
                if (!result.containsKey(grid)) {
                    result.put(grid, new ArrayList<>());
                }
                result.get(grid).add(contact);
            }
        }

        return result;
    }

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return false;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return true;
    }
}