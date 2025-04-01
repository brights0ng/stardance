package net.stardance.render;

import com.bulletphysics.collision.dispatch.CollisionObject;
import com.bulletphysics.collision.shapes.*;
import com.bulletphysics.dynamics.DynamicsWorld;
import com.bulletphysics.linearmath.Transform;
import com.bulletphysics.util.ObjectArrayList;
import net.minecraft.client.render.*;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vec3d;
import net.stardance.core.LocalGrid;
import net.stardance.physics.PhysicsEngine;
import net.stardance.utils.ILoggingControl;
import net.stardance.utils.SLogger;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import javax.vecmath.Quat4f;
import javax.vecmath.Vector3f;

/**
 * Renders outlines of Bullet {@link CollisionShape}s (Boxes, Compounds, Meshes, etc.)
 * for debugging. Also provides a debug line method for visualizing a vector from
 * the player's eye or any arbitrary points.
 */
public class CollisionShapeRenderer implements ILoggingControl {

    // ----------------------------------------------
    // LOGGING CONTROL
    // ----------------------------------------------

    @Override
    public boolean stardance$isChatLoggingEnabled() {
        return true;
    }

    @Override
    public boolean stardance$isConsoleLoggingEnabled() {
        return false;
    }

    // ----------------------------------------------
    // PUBLIC STATIC FIELDS
    // ----------------------------------------------

    /** Master toggle for rendering collision shapes. */
    public static boolean ENABLED = false;

    /** The player's (or camera) eye position in world space, used to draw a debug line. */
    public static Vec3d eyePos = new Vec3d(0, 0, 0);

    /** The direction or end offset for the debug line (relative to eyePos). */
    public static Vec3d lookVec = new Vec3d(0, 0, 0);

    /** If set, a debug line is drawn from eyePos along lookVec. */
    public static LocalGrid interactedGrid = null;

    // ----------------------------------------------
    // PUBLIC RENDER METHOD
    // ----------------------------------------------

    /**
     * Main entry to render collision shapes for all Bullet {@link CollisionObject}s
     * in the provided {@link DynamicsWorld}.
     *
     * @param dynamicsWorld   the bullet world containing collision objects
     * @param matrices        the current matrix stack
     * @param vertexConsumers the vertex consumer providers
     * @param tickDelta       partial tick for interpolation
     * @param engine          reference to the {@link PhysicsEngine}, used for locking
     */
    public void render(DynamicsWorld dynamicsWorld,
                       MatrixStack matrices,
                       VertexConsumerProvider vertexConsumers,
                       float tickDelta,
                       PhysicsEngine engine) {

        if (!ENABLED) return;

        // Synchronize to avoid modifying bullet objects while rendering them
        synchronized (engine.getPhysicsLock()) {
            ObjectArrayList<CollisionObject> snapshot = new ObjectArrayList<>();
            snapshot.addAll(dynamicsWorld.getCollisionObjectArray());

            // Render each collision object
            int numObjects = dynamicsWorld.getNumCollisionObjects();
            for (int i = 0; i < numObjects; i++) {
                CollisionObject collisionObject = snapshot.getQuick(i);
                renderCollisionObject(collisionObject, matrices, vertexConsumers);
            }
        }

        // If there's an interactedGrid, draw a debug line from eyePos -> eyePos + lookVec
        if (interactedGrid != null) {
            matrices.push();
            drawDebugLine(
                    vertexConsumers.getBuffer(RenderLayer.LINES),
                    matrices.peek().getPositionMatrix(),
                    new Vector3f((float) eyePos.x, (float) eyePos.y, (float) eyePos.z),
                    new Vector3f((float) lookVec.x, (float) lookVec.y, (float) lookVec.z)
            );
            SLogger.log(this, "DRAWN: " + eyePos + ", " + lookVec);
            matrices.pop();
        }
    }

    // ----------------------------------------------
    // PRIVATE RENDERING HELPERS
    // ----------------------------------------------

    /**
     * Renders a single Bullet {@link CollisionObject} by applying its transform
     * and drawing its CollisionShape.
     */
    private void renderCollisionObject(CollisionObject collisionObject,
                                       MatrixStack matrices,
                                       VertexConsumerProvider vertexConsumers) {

        CollisionShape shape = collisionObject.getCollisionShape();
        Transform transform = new Transform();
        collisionObject.getWorldTransform(transform);

        matrices.push(); // Save matrix state

        // Apply Bullet transform (translation + rotation)
        applyBulletTransform(transform, matrices);

        // Render the shape (relative to the newly applied transform)
        VertexConsumer vertexConsumer = vertexConsumers.getBuffer(RenderLayer.getLines());
        renderCollisionShape(shape, new Transform(), matrices, vertexConsumer);

        matrices.pop(); // Restore matrix state
    }

    /**
     * Recursively renders a CollisionShape, handling known types:
     * - {@link CompoundShape}
     * - {@link BoxShape}
     * - {@link BvhTriangleMeshShape}
     */
    private void renderCollisionShape(CollisionShape shape,
                                      Transform transform,
                                      MatrixStack matrices,
                                      VertexConsumer vertexConsumer) {

        if (shape instanceof CompoundShape) {
            renderCompoundShape((CompoundShape) shape, transform, matrices, vertexConsumer);

        } else if (shape instanceof BoxShape) {
            renderBoxShape((BoxShape) shape, transform, matrices, vertexConsumer);

        } else if (shape instanceof BvhTriangleMeshShape) {
            renderBvhTriangleMeshShape((BvhTriangleMeshShape) shape, transform, matrices, vertexConsumer);
        }
        // Add other shape types if needed
    }

    /**
     * Renders a {@link CompoundShape} by iterating its child shapes
     * and combining transforms with the parent transform.
     */
    private void renderCompoundShape(CompoundShape compoundShape,
                                     Transform parentTransform,
                                     MatrixStack matrices,
                                     VertexConsumer vertexConsumer) {

        int numChildShapes = compoundShape.getNumChildShapes();

        for (int i = 0; i < numChildShapes; i++) {
            CollisionShape childShape = compoundShape.getChildShape(i);
            Transform childTransform = new Transform();
            compoundShape.getChildTransform(i, childTransform);

            // Combine parent and child transforms
            Transform combinedTransform = new Transform();
            combinedTransform.mul(parentTransform, childTransform);

            matrices.push();

            // Apply the child transform on top of the current matrix
            applyBulletTransform(childTransform, matrices);

            // Render the child shape
            renderCollisionShape(childShape, new Transform(), matrices, vertexConsumer);

            matrices.pop();
        }
    }

    /**
     * Renders a {@link BoxShape} as wireframe lines by connecting its 8 corners.
     */
    private static void renderBoxShape(BoxShape boxShape,
                                       Transform transform,
                                       MatrixStack matrices,
                                       VertexConsumer vertexConsumer) {

        // Extract half extents
        Vector3f halfExtents = new Vector3f();
        boxShape.getHalfExtentsWithMargin(halfExtents);

        // Prepare the 8 corners (local coordinates)
        float hx = halfExtents.x;
        float hy = halfExtents.y;
        float hz = halfExtents.z;
        Vector3f[] vertices = new Vector3f[] {
                new Vector3f(-hx, -hy, -hz), new Vector3f( hx, -hy, -hz),
                new Vector3f( hx,  hy, -hz), new Vector3f(-hx,  hy, -hz),
                new Vector3f(-hx, -hy,  hz), new Vector3f( hx, -hy,  hz),
                new Vector3f( hx,  hy,  hz), new Vector3f(-hx,  hy,  hz)
        };

        // If transform != identity, apply it to each corner
        if (!transform.equals(new Transform())) {
            for (Vector3f vertex : vertices) {
                transform.transform(vertex);
            }
        }

        // Build the 12 edges by connecting corners
        int[][] edges = new int[][] {
                {0,1},{1,2},{2,3},{3,0}, // Bottom face
                {4,5},{5,6},{6,7},{7,4}, // Top face
                {0,4},{1,5},{2,6},{3,7}  // Sides
        };

        // Grab the matrix for rendering lines
        MatrixStack.Entry entry = matrices.peek();
        Matrix4f modelMatrix = entry.getPositionMatrix();

        // Box color
        int red = 0, green = 100, blue = 255, alpha = 255;

        // Draw each edge
        for (int[] edge : edges) {
            drawLine(vertexConsumer, modelMatrix, vertices[edge[0]], vertices[edge[1]],
                    red, green, blue, alpha);
        }
    }

    /**
     * Renders a {@link BvhTriangleMeshShape} by iterating all triangles
     * via a {@link TriangleCallback} and drawing each edge as lines.
     */
    private void renderBvhTriangleMeshShape(BvhTriangleMeshShape meshShape,
                                            Transform transform,
                                            MatrixStack matrices,
                                            VertexConsumer vertexConsumer) {

        MatrixStack.Entry entry = matrices.peek();
        Matrix4f modelMatrix = entry.getPositionMatrix();

        // Process all triangles. If needed, you could limit the AABB
        // for efficiency, but here it's set to the entire shape.
        meshShape.processAllTriangles(
                new TriangleCallback() {
                    @Override
                    public void processTriangle(Vector3f[] triangle, int partId, int triangleIndex) {
                        // 'triangle' is now a Vector3f[] array
                        Vector3f v0 = new Vector3f(triangle[0]);
                        Vector3f v1 = new Vector3f(triangle[1]);
                        Vector3f v2 = new Vector3f(triangle[2]);

                        // Apply transform if not identity
                        if (!transform.equals(new Transform())) {
                            transform.transform(v0);
                            transform.transform(v1);
                            transform.transform(v2);
                        }

                        // Triangle color
                        int red = 0, green = 255, blue = 0, alpha = 255;

                        // Draw 3 edges
                        drawLine(vertexConsumer, modelMatrix, v0, v1, red, green, blue, alpha);
                        drawLine(vertexConsumer, modelMatrix, v1, v2, red, green, blue, alpha);
                        drawLine(vertexConsumer, modelMatrix, v2, v0, red, green, blue, alpha);                    }
                },
                new Vector3f(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE),
                new Vector3f(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        );
    }

    // ----------------------------------------------
    // STATIC UTILITY METHODS
    // ----------------------------------------------

    /**
     * Draws a single line from 'start' to 'end' in the specified color/alpha.
     */
    private static void drawLine(VertexConsumer vertexConsumer,
                                 Matrix4f matrix,
                                 Vector3f start,
                                 Vector3f end,
                                 int red,
                                 int green,
                                 int blue,
                                 int alpha) {

        // First vertex
        vertexConsumer.vertex(matrix, start.x, start.y, start.z)
                .color(red, green, blue, alpha)
                .normal(0, 1, 0)
                .next();

        // Second vertex
        vertexConsumer.vertex(matrix, end.x, end.y, end.z)
                .color(red, green, blue, alpha)
                .normal(0, 1, 0)
                .next();
    }

    /**
     * Public helper to draw a simple debug line in the same style as collision outlines.
     *
     * @param vertexConsumer line buffer (e.g., from {@link RenderLayer#getLines()})
     * @param matrix         current transformation matrix (from {@code matrices.peek().getPositionMatrix()})
     * @param start          starting point
     * @param end            ending point
     */
    public static void drawDebugLine(VertexConsumer vertexConsumer,
                                     Matrix4f matrix,
                                     Vector3f start,
                                     Vector3f end) {
        // Debug line color: red
        int red = 255, green = 0, blue = 0, alpha = 255;
        drawLine(vertexConsumer, matrix, start, end, red, green, blue, alpha);
    }

    /**
     * Applies a Bullet {@link Transform} (translation + rotation) to a Fabric/MC {@link MatrixStack}.
     */
    private static void applyBulletTransform(Transform transform, MatrixStack matrices) {
        // Translate by transform.origin
        Vector3f origin = transform.origin;
        matrices.translate(origin.x, origin.y, origin.z);

        // Extract rotation
        Quat4f bulletRot = new Quat4f();
        transform.getRotation(bulletRot);
        Quaternionf rotation = new Quaternionf(bulletRot.x, bulletRot.y, bulletRot.z, bulletRot.w);

        // Multiply the matrix stack by the rotation
        matrices.multiply(rotation);
    }
}
