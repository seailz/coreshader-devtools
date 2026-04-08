package com.seailz.csdt.client.service;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.ToastManager;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Method;

public final class ClientToastService {

    private ClientToastService() {
    }

    public static void showReloadResult(ShaderReloadService.ReloadScope scope, ShaderReloadService.ReloadStat stat) {
        Component title = Component.literal(scope.label() + " shader" + (scope.label().equalsIgnoreCase("All") ? "s" : "") + " reload");
        Component message = stat.success()
                ? Component.literal("Completed in " + stat.durationMillis() + " ms")
                : Component.literal("Failed: " + stat.message());
        show(title, message);
        if (!stat.success()) {
            showChatMessage(Component.literal("[Shader DevTools] " + title.getString() + " failed: " + stat.message()));
        }
    }

    public static void showOverridesApplied(int activeOverrideCount) {
        Component title = Component.literal("Globals overrides updated");
        Component message = activeOverrideCount > 0
                ? Component.literal(activeOverrideCount + " override(s) active")
                : Component.literal("No globals overrides active");
        show(title, message);
    }

    public static void showOverridesCleared() {
        show(Component.literal("Globals overrides cleared"), null);
    }

    public static void showInfo(String title, String message) {
        show(Component.literal(title), message == null ? null : Component.literal(message));
    }

    private static void show(Component title, Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        ToastManager toastManager = minecraft.gui.toastManager();
        SystemToast.addOrUpdate(toastManager, SystemToast.SystemToastId.PERIODIC_NOTIFICATION, title, message);
    }

    private static void showChatMessage(Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null) {
            return;
        }
        try {
            Method getChatMethod = minecraft.gui.getClass().getMethod("getChat");
            Object chatComponent = getChatMethod.invoke(minecraft.gui);
            Method addMessageMethod = chatComponent.getClass().getMethod("addMessage", Component.class);
            addMessageMethod.invoke(chatComponent, message);
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
