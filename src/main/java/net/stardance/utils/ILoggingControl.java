package net.stardance.utils;

public interface ILoggingControl {
    boolean stardance$isChatLoggingEnabled();
    boolean stardance$isConsoleLoggingEnabled();
    default String getSimpleName() {
        return getClass().getSimpleName();
    }

}
