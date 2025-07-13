package net.starlight.stardance.physics;

import com.bulletphysics.collision.dispatch.CollisionFlags;
import com.bulletphysics.collision.shapes.BvhTriangleMeshShape;
import com.bulletphysics.collision.shapes.TriangleIndexVertexArray;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.dynamics.RigidBody;
import com.bulletphysics.dynamics.RigidBodyConstructionInfo;
import com.bulletphysics.linearmath.Transform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.starlight.stardance.utils.ILoggingControl;
import net.starlight.stardance.utils.SLogger;  // Assuming you have this logging utility

import javax.vecmath.Vector3f;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static net.starlight.stardance.physics.EngineManager.COLLISION_GROUP_MESH;
import static net.starlight.stardance.physics.EngineManager.COLLISION_MASK_MESH;

public class SubchunkMesh implements ILoggingControl {
    private SubchunkCoordinates coords;
    private TriangleIndexVertexArray meshData;
    private BvhTriangleMeshShape meshShape;
    private RigidBody rigidBody;
    private boolean isDirty;
    private boolean isActive;

    public SubchunkMesh(SubchunkCoordinates coords) {
        this.coords = coords;
        this.isDirty = true;  // Mark as dirty initially
        this.isActive = false;
        SLogger.log(this, "SubchunkMesh created for coords: " + coords);
    }

    /**
     * Generates the collision mesh using a greedy meshing algorithm over all six face directions.
     * This version generates vertices in local coordinates (0..subchunkSize) and then uses the
     * rigid body transform to place the mesh in world space.
     */
    public void generateMesh(ServerLevel world) {
        SLogger.log(this, "Starting mesh generation for subchunk at coords: " + coords);
        List<Vector3f> vertices = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        int subchunkSize = 16; // Assumed dimensions for a subchunk

        // Calculate the base world coordinates for this subchunk.
        // (They are used later as the rigid body transform.)
        int baseX = coords.x * subchunkSize;
        int baseY = coords.y * subchunkSize;
        int baseZ = coords.z * subchunkSize;

        // Process each face direction using greedy meshing.
        for (Direction face : Direction.values()) {
            SLogger.log(this, "Processing face: " + face);
            generateGreedyMeshForFace(world, face, baseX, baseY, baseZ, subchunkSize, vertices, indices);
        }

        SLogger.log(this, "Mesh generation produced " + vertices.size() + " vertices and " + indices.size() + " indices.");
        if (vertices.isEmpty() || indices.isEmpty()) {
            SLogger.log(this, "No geometry generated for subchunk at coords: " + coords);
            isDirty = false;
            return;
        }

        int vertexStride = 3 * 4; // 3 floats per vertex
        int indexStride = 3 * 4;  // 3 ints per triangle

        ByteBuffer vertexByteBuffer = ByteBuffer.allocateDirect(vertices.size() * vertexStride).order(ByteOrder.nativeOrder());
        for (Vector3f v : vertices) {
            vertexByteBuffer.putFloat(v.x);
            vertexByteBuffer.putFloat(v.y);
            vertexByteBuffer.putFloat(v.z);
        }
        vertexByteBuffer.flip();

        ByteBuffer indexByteBuffer = ByteBuffer.allocateDirect(indices.size() * 4).order(ByteOrder.nativeOrder());
        for (int i : indices) {
            indexByteBuffer.putInt(i);
        }
        indexByteBuffer.flip();

        meshData = new TriangleIndexVertexArray(
                indices.size() / 3,
                indexByteBuffer,
                indexStride,
                vertices.size(),
                vertexByteBuffer,
                vertexStride
        );
        meshShape = new BvhTriangleMeshShape(meshData, true);

        if (rigidBody != null) {
            rigidBody.setCollisionShape(meshShape);
            SLogger.log(this, "Updated collision shape for existing rigid body.");
        } else {
            RigidBodyConstructionInfo rbInfo = new RigidBodyConstructionInfo(0, null, meshShape);
            rigidBody = new RigidBody(rbInfo);
            SLogger.log(this, "Created new rigid body for subchunk.");
        }

        // Set the rigid body's transform to the subchunk's base world coordinates.
        Transform transform = new Transform();
        transform.setIdentity();
        transform.origin.set(new Vector3f(baseX, baseY, baseZ));
        rigidBody.setWorldTransform(transform);
        SLogger.log(this, "Rigid body transform set to (" + baseX + ", " + baseY + ", " + baseZ + ").");

        isDirty = false;
    }

    /**
     * For a given face direction, iterates through the subchunk slices, builds a 2D mask of exposed faces,
     * and processes that mask with the greedy meshing algorithm.
     */
    private void generateGreedyMeshForFace(ServerLevel world, Direction face, int baseX, int baseY, int baseZ, int subchunkSize, List<Vector3f> vertices, List<Integer> indices) {
        switch(face) {
            case UP:
                for (int y = 0; y < subchunkSize; y++) {
                    boolean[][] mask = new boolean[subchunkSize][subchunkSize];
                    for (int x = 0; x < subchunkSize; x++) {
                        for (int z = 0; z < subchunkSize; z++) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            BlockState state = world.getBlockState(pos);
                            BlockPos neighborPos = new BlockPos(baseX + x, baseY + y + 1, baseZ + z);
                            mask[x][z] = (!state.isAir()) && world.getBlockState(neighborPos).isAir();
                        }
                    }
                    processMask(mask, face, baseX, baseY, baseZ, subchunkSize, y, vertices, indices);
                }
                break;
            case DOWN:
                for (int y = 0; y < subchunkSize; y++) {
                    boolean[][] mask = new boolean[subchunkSize][subchunkSize];
                    for (int x = 0; x < subchunkSize; x++) {
                        for (int z = 0; z < subchunkSize; z++) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            BlockState state = world.getBlockState(pos);
                            BlockPos neighborPos = new BlockPos(baseX + x, baseY + y - 1, baseZ + z);
                            mask[x][z] = (!state.isAir()) && world.getBlockState(neighborPos).isAir();
                        }
                    }
                    processMask(mask, face, baseX, baseY, baseZ, subchunkSize, y, vertices, indices);
                }
                break;
            case NORTH:
                for (int z = 0; z < subchunkSize; z++) {
                    boolean[][] mask = new boolean[subchunkSize][subchunkSize];
                    for (int x = 0; x < subchunkSize; x++) {
                        for (int y = 0; y < subchunkSize; y++) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            BlockState state = world.getBlockState(pos);
                            BlockPos neighborPos = new BlockPos(baseX + x, baseY + y, baseZ + z - 1);
                            mask[x][y] = (!state.isAir()) && world.getBlockState(neighborPos).isAir();
                        }
                    }
                    processMask(mask, face, baseX, baseY, baseZ, subchunkSize, z, vertices, indices);
                }
                break;
            case SOUTH:
                for (int z = 0; z < subchunkSize; z++) {
                    boolean[][] mask = new boolean[subchunkSize][subchunkSize];
                    for (int x = 0; x < subchunkSize; x++) {
                        for (int y = 0; y < subchunkSize; y++) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            BlockState state = world.getBlockState(pos);
                            BlockPos neighborPos = new BlockPos(baseX + x, baseY + y, baseZ + z + 1);
                            mask[x][y] = (!state.isAir()) && world.getBlockState(neighborPos).isAir();
                        }
                    }
                    processMask(mask, face, baseX, baseY, baseZ, subchunkSize, z, vertices, indices);
                }
                break;
            case WEST:
                for (int x = 0; x < subchunkSize; x++) {
                    boolean[][] mask = new boolean[subchunkSize][subchunkSize];
                    for (int z = 0; z < subchunkSize; z++) {
                        for (int y = 0; y < subchunkSize; y++) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            BlockState state = world.getBlockState(pos);
                            BlockPos neighborPos = new BlockPos(baseX + x - 1, baseY + y, baseZ + z);
                            mask[z][y] = (!state.isAir()) && world.getBlockState(neighborPos).isAir();
                        }
                    }
                    processMask(mask, face, baseX, baseY, baseZ, subchunkSize, x, vertices, indices);
                }
                break;
            case EAST:
                for (int x = 0; x < subchunkSize; x++) {
                    boolean[][] mask = new boolean[subchunkSize][subchunkSize];
                    for (int z = 0; z < subchunkSize; z++) {
                        for (int y = 0; y < subchunkSize; y++) {
                            BlockPos pos = new BlockPos(baseX + x, baseY + y, baseZ + z);
                            BlockState state = world.getBlockState(pos);
                            BlockPos neighborPos = new BlockPos(baseX + x + 1, baseY + y, baseZ + z);
                            mask[z][y] = (!state.isAir()) && world.getBlockState(neighborPos).isAir();
                        }
                    }
                    processMask(mask, face, baseX, baseY, baseZ, subchunkSize, x, vertices, indices);
                }
                break;
        }
    }

    /**
     * Processes a 2D boolean mask using a greedy rectangle extraction algorithm.
     * The parameters 'i' and 'j' in the mask correspond to block indices in two dimensions.
     * The parameter 'slice' indicates the fixed coordinate for the current face.
     */
    private void processMask(boolean[][] mask, Direction face, int baseX, int baseY, int baseZ, int subchunkSize, int slice, List<Vector3f> vertices, List<Integer> indices) {
        int rows = mask.length;
        int cols = mask[0].length;
        boolean[][] processed = new boolean[rows][cols];
        int quadsGenerated = 0;

        for (int i = 0; i < rows; i++) {
            for (int j = 0; j < cols; j++) {
                if (mask[i][j] && !processed[i][j]) {
                    int width = 1;
                    while (i + width < rows && mask[i + width][j] && !processed[i + width][j]) {
                        width++;
                    }
                    int height = 1;
                    outer:
                    while (j + height < cols) {
                        for (int k = 0; k < width; k++) {
                            if (!mask[i + k][j + height] || processed[i + k][j + height]) {
                                break outer;
                            }
                        }
                        height++;
                    }
                    for (int a = 0; a < width; a++) {
                        for (int b = 0; b < height; b++) {
                            processed[i + a][j + b] = true;
                        }
                    }
                    // Compute quad vertices in local space.
                    Vector3f[] quad = computeQuadVertices(face, baseX, baseY, baseZ, slice, i, j, width, height);
                    int startIndex = vertices.size();
                    vertices.add(quad[0]);
                    vertices.add(quad[1]);
                    vertices.add(quad[2]);
                    vertices.add(quad[3]);
                    indices.add(startIndex);
                    indices.add(startIndex + 1);
                    indices.add(startIndex + 2);
                    indices.add(startIndex);
                    indices.add(startIndex + 2);
                    indices.add(startIndex + 3);
                    quadsGenerated++;
                }
            }
        }
        SLogger.log(this, "Slice " + slice + " for face " + face + " generated " + quadsGenerated + " quads.");
    }

    /**
     * Computes the four vertices for a quad given the face direction and the rectangle within the mask.
     * This version produces vertices in local coordinates (0..subchunkSize) so that the rigid body's
     * transform can be used to position the subchunk in world space.
     */
    private Vector3f[] computeQuadVertices(Direction face, int baseX, int baseY, int baseZ, int slice, int i, int j, int width, int height) {
        Vector3f[] quad = new Vector3f[4];
        switch(face) {
            case UP: {
                // Local: y = slice + 1, x = i .. i+width, z = j .. j+height
                float y = slice + 1;
                float x0 = i;
                float x1 = i + width;
                float z0 = j;
                float z1 = j + height;
                quad[0] = new Vector3f(x0, y, z0);
                quad[1] = new Vector3f(x1, y, z0);
                quad[2] = new Vector3f(x1, y, z1);
                quad[3] = new Vector3f(x0, y, z1);
                break;
            }
            case DOWN: {
                // Local: y = slice, x = i .. i+width, z = j .. j+height
                float y = slice;
                float x0 = i;
                float x1 = i + width;
                float z0 = j;
                float z1 = j + height;
                quad[0] = new Vector3f(x0, y, z0);
                quad[1] = new Vector3f(x1, y, z0);
                quad[2] = new Vector3f(x1, y, z1);
                quad[3] = new Vector3f(x0, y, z1);
                break;
            }
            case NORTH: {
                // Local: z = slice, x = i .. i+width, y = j .. j+height
                float z = slice;
                float x0 = i;
                float x1 = i + width;
                float y0 = j;
                float y1 = j + height;
                quad[0] = new Vector3f(x0, y0, z);
                quad[1] = new Vector3f(x1, y0, z);
                quad[2] = new Vector3f(x1, y1, z);
                quad[3] = new Vector3f(x0, y1, z);
                break;
            }
            case SOUTH: {
                // Local: z = slice + 1, x = i .. i+width, y = j .. j+height
                float z = slice + 1;
                float x0 = i;
                float x1 = i + width;
                float y0 = j;
                float y1 = j + height;
                quad[0] = new Vector3f(x0, y0, z);
                quad[1] = new Vector3f(x1, y0, z);
                quad[2] = new Vector3f(x1, y1, z);
                quad[3] = new Vector3f(x0, y1, z);
                break;
            }
            case WEST: {
                // Local: x = slice, z = i .. i+width, y = j .. j+height
                float x = slice;
                float z0 = i;
                float z1 = i + width;
                float y0 = j;
                float y1 = j + height;
                quad[0] = new Vector3f(x, y0, z0);
                quad[1] = new Vector3f(x, y0, z1);
                quad[2] = new Vector3f(x, y1, z1);
                quad[3] = new Vector3f(x, y1, z0);
                break;
            }
            case EAST: {
                // Local: x = slice + 1, z = i .. i+width, y = j .. j+height
                float x = slice + 1;
                float z0 = i;
                float z1 = i + width;
                float y0 = j;
                float y1 = j + height;
                quad[0] = new Vector3f(x, y0, z0);
                quad[1] = new Vector3f(x, y0, z1);
                quad[2] = new Vector3f(x, y1, z1);
                quad[3] = new Vector3f(x, y1, z0);
                break;
            }
        }
        return quad;
    }

    // Getters and helper methods.
    public RigidBody getRigidBody() {
        return rigidBody;
    }

    public boolean isDirty() {
        return isDirty;
    }

    public boolean isActive() {
        return isActive;
    }

    public void markDirty(){
        isDirty = true;
        SLogger.log(this, "SubchunkMesh marked dirty for coords: " + coords);
    }

    public void addToPhysicsWorld(DynamicsWorld dynamicsWorld) {
        if (!isActive && rigidBody != null) {
            // Set collision flag for custom material callback
            rigidBody.setCollisionFlags(rigidBody.getCollisionFlags() | CollisionFlags.CUSTOM_MATERIAL_CALLBACK);

            // Store a marker with type information for collision detection
            rigidBody.setUserPointer(new WorldBlockMarker(new Vector3f(coords.x * 16, coords.y * 16, coords.z * 16)));

            // Add to dynamics world
            dynamicsWorld.addRigidBody(rigidBody);

            rigidBody.getBroadphaseProxy().collisionFilterGroup = COLLISION_GROUP_MESH;
            rigidBody.getBroadphaseProxy().collisionFilterMask = COLLISION_MASK_MESH;
            isActive = true;
            SLogger.log(this, "Added subchunk at coords " + coords + " to physics world.");
        }
    }

    public void removeFromPhysicsWorld(DynamicsWorld dynamicsWorld) {
        if (isActive) {
            dynamicsWorld.removeRigidBody(rigidBody);
            isActive = false;
            SLogger.log(this, "Removed subchunk at coords " + coords + " from physics world.");
        }
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
