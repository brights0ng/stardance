package net.starlight.stardance.utils;

import net.starlight.stardance.Stardance;
import org.slf4j.Logger;

import static net.starlight.stardance.Stardance.serverInstance;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class SLogger {
    // Reference to your mod's logger
    private static final Logger LOGGER = Stardance.LOGGER;

    // Method to log a message based on ILoggingControl
    public static void log(ILoggingControl logger, String message) {
        String logMessage = "[" + logger.getSimpleName() + "] " + message;

        // Log to console if enabled
        if (logger.stardance$isConsoleLoggingEnabled() && !logger.stardance$isChatLoggingEnabled()) {
            LOGGER.info(logMessage);
        }

        // Send to chat if enabled
        if (logger.stardance$isChatLoggingEnabled() & serverInstance != null) {
            for (ServerLevel world : serverInstance.getAllLevels()) {
                sendToChat(world, logMessage);
            }
        }
    }

    // Method to log a message based on ILoggingControl
    public static void log(String clazz, String message) {
        String logMessage = "[" + clazz + "] " + message;

        LOGGER.info(logMessage);
    }

    // Method to send a message to all players in the world
    private static void sendToChat(ServerLevel world, String message) {
        Component text = Component.literal(message).withStyle(ChatFormatting.YELLOW);

        for (ServerPlayer player : world.players()) {
            player.displayClientMessage(text, false);
        }
    }
}
