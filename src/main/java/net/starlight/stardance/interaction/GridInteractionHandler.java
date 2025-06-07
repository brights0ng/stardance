package net.starlight.stardance.interaction;

import com.bulletphysics.linearmath.Transform;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.starlight.stardance.core.LocalBlock;
import net.starlight.stardance.core.LocalGrid;
import net.starlight.stardance.render.CollisionShapeRenderer;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;

import javax.vecmath.Vector3d;
import javax.vecmath.Vector3f;
import java.util.Map;
import java.util.Set;

import static net.starlight.stardance.Stardance.engineManager;

/**
 * Handler for interacting with LocalGrids using Minecraft's built-in block placement logic.
 * Uses a hybrid approach combining DDA raycasting with Minecraft's BlockHitResult system.
 */
public class GridInteractionHandler implements ILoggingControl {

    // Maximum distance for player block placement
    float playerReachDistance = 4.5f;

    /**
     * Main entry point, called when a player uses a block.
     */
    public ActionResult onUseBlock(PlayerEntity player, Hand hand) {
        // Only proceed on the server side to avoid double placement
        if (player.getEntityWorld().isClient) {
            return ActionResult.PASS;
        }

        // Only handle main hand to avoid double triggers from off-hand
        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }

        ItemStack heldItem = player.getStackInHand(hand);
        if (!(heldItem.getItem() instanceof BlockItem)) {
            return ActionResult.PASS; // Not holding a block, ignore
        }

        boolean handled = handleGridInteraction(player, hand, (BlockItem) heldItem.getItem());
        return handled ? ActionResult.SUCCESS : ActionResult.PASS;
    }

    /**
     * Handles the player's interaction with a grid using the held block item.
     */
    public boolean handleGridInteraction(PlayerEntity player, Hand hand, BlockItem blockItem) {
        // Get the player's look information
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        SLogger.log(this, "Player eyePos: " + eyePos
                + ", lookVec: " + lookVec
                + ", reachDistance: " + playerReachDistance);

        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));
        SLogger.log(this, "Ray start (world): " + eyePos
                + ", Ray end (world): " + reachPoint);

        // Attempt to find a grid in the player's view
        LocalGrid grid = getGridPlayerIsLookingAt(player);
        if (grid == null) {
            SLogger.log(this, "No grid found in player's view.");
            return false;
        }

        SLogger.log(this, "Player is looking at grid: " + grid);

        // For debugging: log the grid's bounding box
        logGridBoundingBox(grid);

        // Log all known block positions in the grid
        logGridBlockPositions(grid);

        // Perform raycast against the grid using improved Minecraft-like logic
        GridRaycastResult result = performGridRaycast(eyePos, reachPoint, grid);

        if (result != null) {
            SLogger.log(this, "Raycast HIT block at position: " + result.getBlockPosition()
                    + ", face: " + result.getHitFace()
                    + ", world hitPos: " + result.getHitPosition());

            // Place the block in the grid
            placeBlockInGrid(player, grid, result, blockItem);
            return true;
        } else {
            SLogger.log(this, "Raycast did not hit any grid block.");
        }

        return false;
    }

    /**
     * Performs a raycast against the grid, using a hybrid of DDA and Minecraft's BlockHitResult.
     * This approach first identifies which block we hit, then uses Minecraft's more refined
     * approach to determine the exact hit position and face.
     */
    public GridRaycastResult performGridRaycast(Vec3d startWorld, Vec3d endWorld, LocalGrid grid) {
        SLogger.log(this, "----- performGridRaycast START -----");
        SLogger.log(this, "Input start (world): " + startWorld + ", end (world): " + endWorld);

        // Convert to grid-local coordinate space
        Vector3d startVec = grid.worldToGridLocal(new Vector3d(startWorld.x, startWorld.y, startWorld.z));
        Vector3d endVec = grid.worldToGridLocal(new Vector3d(endWorld.x, endWorld.y, endWorld.z));

        SLogger.log(this, "After transform -> startVec (grid-local): " + startVec
                + ", endVec (grid-local): " + endVec);

        Vec3d startLocal = new Vec3d(startVec.x, startVec.y, startVec.z);
        Vec3d endLocal = new Vec3d(endVec.x, endVec.y, endVec.z);

        // 1. First use DDA to find which block was hit
        BlockPos hitBlockPos = findHitBlockUsingDDA(startLocal, endLocal, grid);
        if (hitBlockPos == null) {
            SLogger.log(this, "DDA found no hit block");
            return null;
        }

        SLogger.log(this, "DDA found hit block at " + hitBlockPos);

        // 2. Use Minecraft's BlockHitResult to determine exact hit position and face
        BlockHitResult mcHitResult = calculateBlockHitResult(startLocal, endLocal, hitBlockPos, grid);
        if (mcHitResult == null) {
            SLogger.log(this, "Failed to create BlockHitResult");
            return null;
        }

        // 3. Extract information from the hit result
        BlockPos exactHitPos = mcHitResult.getBlockPos();
        Direction hitFace = mcHitResult.getSide();
        Vec3d hitPosLocal = mcHitResult.getPos();

        // 4. Convert hit position back to world space
        Vec3d hitPosWorld = convertLocalToWorld(hitPosLocal, grid);

        SLogger.log(this, "Final hit result: blockPos=" + exactHitPos
                + ", face=" + hitFace
                + ", local hit pos=" + hitPosLocal
                + ", world hit pos=" + hitPosWorld);

        // For debug rendering
        CollisionShapeRenderer.eyePos = startLocal;
        CollisionShapeRenderer.lookVec = endLocal.subtract(startLocal);

        return new GridRaycastResult(exactHitPos, hitFace, hitPosWorld);
    }

    /**
     * Transforms a local position to world space using the grid's transform.
     */
    private Vec3d convertLocalToWorld(Vec3d localPos, LocalGrid grid) {
        Vector3f localVec = new Vector3f((float)localPos.x, (float)localPos.y, (float)localPos.z);
        Transform tf = new Transform();
        grid.getRigidBody().getWorldTransform(tf);
        tf.transform(localVec);
        return new Vec3d(localVec.x, localVec.y, localVec.z);
    }

    /**
     * Uses DDA algorithm to find which block in the grid is hit by the ray.
     * This only determines the block position, not the exact hit point or face.
     */
    private BlockPos findHitBlockUsingDDA(Vec3d startLocal, Vec3d endLocal, LocalGrid grid) {
        SLogger.log(this, "----- findHitBlockUsingDDA START -----");

        // Direction and distance
        Vec3d ray = endLocal.subtract(startLocal);
        double totalDist = ray.length();

        // If distance is effectively zero, no movement
        if (totalDist < 1e-8) {
            SLogger.log(this, "Ray is zero-length; no blocks hit.");
            return null;
        }

        // Normalize for stepping
        Vec3d dir = ray.normalize();

        // Current param 't' will go from 0..totalDist
        double t = 0.0;

        // Starting integer voxel coordinates
        int x = MathHelper.floor(startLocal.x);
        int y = MathHelper.floor(startLocal.y);
        int z = MathHelper.floor(startLocal.z);

        // Step signs
        int stepX = dir.x > 0 ? 1 : (dir.x < 0 ? -1 : 0);
        int stepY = dir.y > 0 ? 1 : (dir.y < 0 ? -1 : 0);
        int stepZ = dir.z > 0 ? 1 : (dir.z < 0 ? -1 : 0);

        // Avoid division by zero
        double dx = Math.abs(dir.x) < 1e-8 ? 1e-8 : dir.x;
        double dy = Math.abs(dir.y) < 1e-8 ? 1e-8 : dir.y;
        double dz = Math.abs(dir.z) < 1e-8 ? 1e-8 : dir.z;

        // Distance in param 't' to cross one block boundary in each axis
        double tDeltaX = Math.abs(1.0 / dx);
        double tDeltaY = Math.abs(1.0 / dy);
        double tDeltaZ = Math.abs(1.0 / dz);

        // Figure out how far to the *first* boundary in each axis
        double tx = nextTForAxis(startLocal.x, x, stepX, dx);
        double ty = nextTForAxis(startLocal.y, y, stepY, dy);
        double tz = nextTForAxis(startLocal.z, z, stepZ, dz);

        // Step until we exceed totalDist or find a block
        while (t <= totalDist) {
            // Check if there's a block at (x,y,z)
            BlockPos pos = new BlockPos(x, y, z);
            if (grid.getBlocks().containsKey(pos)) {
                SLogger.log(this, "DDA found hit at block " + pos);
                return pos;
            }

            // Move to the next boundary in whichever axis is smallest
            if (tx < ty && tx < tz) {
                x += stepX;
                t = tx;
                tx += tDeltaX;
            } else if (ty < tz) {
                y += stepY;
                t = ty;
                ty += tDeltaY;
            } else {
                z += stepZ;
                t = tz;
                tz += tDeltaZ;
            }

            // If we exceed totalDist, no hit
            if (t > totalDist) {
                SLogger.log(this, "DDA exceeded totalDist without hitting a block.");
                break;
            }
        }

        // No blocks hit
        SLogger.log(this, "No block was hit by DDA.");
        return null;
    }

    /**
     * Computes the param 't' at which the ray first crosses an integer boundary in one axis.
     */
    private double nextTForAxis(double startCoord, int blockCoord, int step, double direction) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        // If step>0, the next boundary is (blockCoord+1).
        // If step<0, the next boundary is blockCoord (the 'left' side).
        double boundary = (step > 0) ? (blockCoord + 1) : (blockCoord);
        double distToBoundary = boundary - startCoord;
        return distToBoundary / direction;
    }

    /**
     * Creates a BlockHitResult using Minecraft's block shape collision logic.
     * This mimics how Minecraft determines hit positions and faces.
     */
    private BlockHitResult calculateBlockHitResult(Vec3d startLocal, Vec3d endLocal, BlockPos blockPos, LocalGrid grid) {
        // Get the block state from our grid
        BlockState blockState = grid.getBlock(blockPos);
        if (blockState == null) {
            SLogger.log(this, "No BlockState found at position " + blockPos);
            return null;
        }

        // Create a voxel shape for the block
        // In a complete implementation, we'd get this from blockState.getOutlineShape(),
        // but for simplicity we'll use a full block shape
        VoxelShape shape = VoxelShapes.fullCube();

        // Calculate ray intersection with the shape
        // This mimics Minecraft's BlockView.raycast() logic
        BlockHitResult hitResult = raycastShape(shape, startLocal, endLocal, blockPos);

        if (hitResult == null || hitResult.getType() == BlockHitResult.Type.MISS) {
            SLogger.log(this, "Shape raycast missed or returned null");

            // Fallback for when the shape raycast fails
            Direction face = getClosestFace(startLocal, blockPos);
            SLogger.log(this, "Using fallback closest face: " + face);

            // Create a missed hit result with the closest face
            return BlockHitResult.createMissed(
                    endLocal,
                    face,
                    blockPos
            );
        }

        SLogger.log(this, "Shape raycast hit at " + hitResult.getPos() + ", side " + hitResult.getSide());
        return hitResult;
    }

    /**
     * Performs a raycast against a VoxelShape. This mimics the functionality of
     * VoxelShape.raycast() in Minecraft's code.
     */
    private BlockHitResult raycastShape(VoxelShape shape, Vec3d start, Vec3d end, BlockPos pos) {
        // This is a simplified implementation of VoxelShape.raycast()
        // A full implementation would handle all the complexities of VoxelShape

        // Adjust ray for block position
        Vec3d relStart = start.subtract(Vec3d.of(pos));
        Vec3d relEnd = end.subtract(Vec3d.of(pos));

        // Simple box raycast since we're using a full cube shape
        // In a real implementation, you'd use shape.raycast() directly
        return raycastBox(relStart, relEnd, pos);
    }

    /**
     * Performs a simple box raycast. This is a standalone implementation
     * for when we can't use Minecraft's actual VoxelShape.raycast().
     */
    private BlockHitResult raycastBox(Vec3d start, Vec3d end, BlockPos pos) {
        // Ray direction
        Vec3d dir = end.subtract(start).normalize();

        // For each dimension, calculate intersection with the box faces
        double tMinX = intersectBox(start.x, dir.x, 0, 1);
        double tMinY = intersectBox(start.y, dir.y, 0, 1);
        double tMinZ = intersectBox(start.z, dir.z, 0, 1);

        // Find the furthest entry point (latest entry = actual entry)
        double tMin = Math.max(Math.max(tMinX, tMinY), tMinZ);

        // If tMin is negative, ray starts inside the box or goes away from it
        if (tMin < 0) {
            SLogger.log(this, "Ray starts inside box or goes away from it");
            return null;
        }

        // Determine which face was hit (the one with the maximum tMin)
        Direction face;
        if (tMin == tMinX) {
            face = dir.x > 0 ? Direction.WEST : Direction.EAST;
        } else if (tMin == tMinY) {
            face = dir.y > 0 ? Direction.DOWN : Direction.UP;
        } else { // tMin == tMinZ
            face = dir.z > 0 ? Direction.NORTH : Direction.SOUTH;
        }

        // Calculate hit position
        Vec3d hitPos = start.add(dir.multiply(tMin));

        // Adjust hit pos to world coordinates
        Vec3d worldHitPos = hitPos.add(Vec3d.of(pos));

        return new BlockHitResult(worldHitPos, face, pos, false);
    }

    /**
     * Helper method for box intersection in a single dimension.
     */
    private double intersectBox(double start, double dir, double min, double max) {
        // Special case: ray parallel to slab
        if (Math.abs(dir) < 1e-8) {
            return (start >= min && start <= max) ? 0 : Double.NEGATIVE_INFINITY;
        }

        // Calculate intersection with both planes
        double t1 = (min - start) / dir;
        double t2 = (max - start) / dir;

        // Ensure t1 is the entry point and t2 is the exit point
        if (t1 > t2) {
            double temp = t1;
            t1 = t2;
            t2 = temp;
        }

        return t1;
    }

    /**
     * Find the closest face to a point for a given block position.
     */
    private Direction getClosestFace(Vec3d point, BlockPos pos) {
        // Calculate block-relative coordinates
        double x = point.x - pos.getX();
        double y = point.y - pos.getY();
        double z = point.z - pos.getZ();

        // Distance to each face (if inside the block, distance is negative)
        double distWest = x;
        double distEast = 1 - x;
        double distDown = y;
        double distUp = 1 - y;
        double distNorth = z;
        double distSouth = 1 - z;

        // Find minimum positive distance
        double minDist = Double.MAX_VALUE;
        Direction closestFace = Direction.UP; // Default

        if (distWest > 0 && distWest < minDist) {
            minDist = distWest;
            closestFace = Direction.WEST;
        }
        if (distEast > 0 && distEast < minDist) {
            minDist = distEast;
            closestFace = Direction.EAST;
        }
        if (distDown > 0 && distDown < minDist) {
            minDist = distDown;
            closestFace = Direction.DOWN;
        }
        if (distUp > 0 && distUp < minDist) {
            minDist = distUp;
            closestFace = Direction.UP;
        }
        if (distNorth > 0 && distNorth < minDist) {
            minDist = distNorth;
            closestFace = Direction.NORTH;
        }
        if (distSouth > 0 && distSouth < minDist) {
            minDist = distSouth;
            closestFace = Direction.SOUTH;
        }

        // If all distances are negative (point inside block), find closest face
        if (minDist == Double.MAX_VALUE) {
            minDist = Math.min(Math.min(Math.min(-distWest, -distEast),
                            Math.min(-distDown, -distUp)),
                    Math.min(-distNorth, -distSouth));

            if (minDist == -distWest) return Direction.WEST;
            if (minDist == -distEast) return Direction.EAST;
            if (minDist == -distDown) return Direction.DOWN;
            if (minDist == -distUp) return Direction.UP;
            if (minDist == -distNorth) return Direction.NORTH;
            if (minDist == -distSouth) return Direction.SOUTH;
        }

        return closestFace;
    }

    /**
     * Actually place the block in the LocalGrid's block map if possible.
     */
    public void placeBlockInGrid(PlayerEntity player, LocalGrid grid,
                                 GridRaycastResult result, BlockItem blockItem) {
        BlockPos hitPosition = result.getBlockPosition();
        Direction hitFace = result.getHitFace();

        // Calculate the position to place the new block
        BlockPos placePosition = hitPosition.offset(hitFace);

        SLogger.log(this, "Attempting to place block at position: " + placePosition
                + ", hit face: " + hitFace);

        if (!grid.getBlocks().containsKey(placePosition)) {
            BlockState blockState = blockItem.getBlock().getDefaultState();
            LocalBlock localBlock = new LocalBlock(placePosition, blockState);

            grid.addBlock(localBlock);

            SLogger.log(this, "Placed block at position: " + placePosition);

            if (!player.isCreative()) {
                player.getStackInHand(Hand.MAIN_HAND).decrement(1);
            }
        } else {
            player.sendMessage(Text.literal("Cannot place block here"), true);
            SLogger.log(this, "Cannot place block at position: "
                    + placePosition + " - position already occupied.");
        }
    }

    /**
     * Finds the LocalGrid the player is looking at by checking which grid's AABB
     * intersects the player's main-hand view ray, or whatever logic you prefer.
     */
    public LocalGrid getGridPlayerIsLookingAt(PlayerEntity player) {
        Set<LocalGrid> grids = engineManager.getEngine(player.getWorld()).getGrids();

        SLogger.log(this, "Checking " + grids.size() + " grids for intersection with player's ray...");
        Vec3d eyePos = player.getEyePos();
        Vec3d lookVec = player.getRotationVec(1.0F);
        Vec3d reachPoint = eyePos.add(lookVec.multiply(playerReachDistance));

        LocalGrid found = null;
        for (LocalGrid grid : grids) {
            if (gridIntersectsRay(grid, eyePos, reachPoint)) {
                SLogger.log(this, "Found grid in player's view: " + grid);
                found = grid;
                break; // just take the first we find
            }
        }
        return found;
    }

    /**
     * Use the grid's AABB in world space to quickly test if the ray intersects.
     * This is optional. If you skip bounding-box tests, you can just DDA everything,
     * but that might be less efficient.
     */
    private boolean gridIntersectsRay(LocalGrid grid, Vec3d start, Vec3d end) {
        // Get the grid's bounding box in world coords
        Vector3f minAabb = new Vector3f();
        Vector3f maxAabb = new Vector3f();
        grid.getAABB(minAabb, maxAabb);

        // Convert to Minecraft's AABB
        Box gridBox = new Box(minAabb.x, minAabb.y, minAabb.z,
                maxAabb.x, maxAabb.y, maxAabb.z);

        boolean intersects = gridBox.intersects(start, end);
        SLogger.log(this, "Checking intersection with grid AABB: " + gridBox
                + ", intersects: " + intersects);
        return intersects;
    }

    /**
     * Logging: bounding box
     */
    private void logGridBoundingBox(LocalGrid grid) {
        Vector3f minAabb = new Vector3f();
        Vector3f maxAabb = new Vector3f();
        grid.getAABB(minAabb, maxAabb);
        SLogger.log(this, "Grid bounding box in world coords: min="
                + vec3fToString(minAabb)
                + ", max="
                + vec3fToString(maxAabb));
    }

    /**
     * Logging: list all blocks in the grid.
     */
    private void logGridBlockPositions(LocalGrid grid) {
        Map<BlockPos, LocalBlock> blocks = grid.getBlocks();
        SLogger.log(this, "Logging " + blocks.size() + " blocks in grid:");
        for (BlockPos pos : blocks.keySet()) {
            SLogger.log(this, "  - " + pos + " " + blocks.get(pos).getState());
        }
    }

    private String vec3fToString(Vector3f vec) {
        return String.format("(%.3f, %.3f, %.3f)", vec.x, vec.y, vec.z);
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