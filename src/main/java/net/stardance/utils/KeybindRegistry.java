package net.stardance.utils;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.stardance.render.CollisionShapeRenderer;
import org.lwjgl.glfw.GLFW;

public class KeybindRegistry {

    private static KeyBinding toggleDebugKey;
    private static KeyBinding toggleDebugRenderingKey;


    public static void registerKeyBindings() {
        toggleDebugKey = new KeyBinding(
                "key.stardance.toggle_debug",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F9,
                "category.stardance"
        );
        toggleDebugRenderingKey = new KeyBinding(
                "key.stardance.toggle_debug_rendering",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_F10, // F10 key
                "category.stardance"
        );

        KeyBindingHelper.registerKeyBinding(toggleDebugKey);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleDebugKey.wasPressed()) {
                CollisionShapeRenderer.ENABLED = !CollisionShapeRenderer.ENABLED;
                client.player.sendMessage(Text.literal("Debug renderer " + (CollisionShapeRenderer.ENABLED ? "enabled" : "disabled") + "."), true);
            }
        });
    }
}
