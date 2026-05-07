package com.seailz.csdt.client.service;

import com.seailz.csdt.client.state.GlobalsOverrideState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ShaderDebugInfoService {

    private ShaderDebugInfoService() {
    }

    public static List<String> buildDebugLines(Minecraft minecraft) {
        List<String> lines = new ArrayList<>();
        try {
            lines.add(section("Renderer"));
            lines.add("FPS: " + minecraft.getFps() + " (" + formatFrameTime(minecraft.getFps()) + ")");
            lines.add("Screen: " + minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight());
            lines.add("GUI scale: " + minecraft.getWindow().getGuiScale());
            lines.add("Level loaded: " + (minecraft.level != null));
            lines.add("Rendered sections: " + safeValue(() -> Integer.toString(minecraft.levelRenderer.visibleSections().size())));
            lines.add("All sections ready: " + safeValue(() -> yesNo(minecraft.levelRenderer.hasRenderedAllSections())));
            lines.add("Active post effect: " + safeValue(() -> {
                Identifier postEffect = minecraft.gameRenderer.currentPostEffect();
                return postEffect == null ? "None" : postEffect.toString();
            }));

            lines.add("");
            lines.add(section("World"));
            ClientLevel level = minecraft.level;
            if (level == null) {
                lines.add("World: Not in game");
            } else {
                lines.add("Game time: " + (level.getGameTime() % 24000L) + " / 24000");
                lines.add("Rain: " + yesNo(level.isRaining()) + ", Thunder: " + yesNo(level.isThundering()));
            }
            LocalPlayer player = minecraft.player;
            if (player == null) {
                lines.add("Player: Unavailable");
            } else {
                BlockPos pos = player.blockPosition();
                lines.add("Player pos: " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ());
                lines.add("Player xyz: %.2f, %.2f, %.2f".formatted(player.getX(), player.getY(), player.getZ()));
            }

            lines.add("");
            lines.add(section("Overrides"));
            List<String> overrides = GlobalsOverrideState.getInstance().describeActiveOverrides();
            if (overrides.isEmpty()) {
                lines.add("No active globals overrides");
            } else {
                lines.addAll(overrides);
            }

            lines.add("");
            lines.add(section("Reload Stats"));
            for (ShaderReloadService.ReloadScope scope : ShaderReloadService.ReloadScope.values()) {
                ShaderReloadService.ReloadStat stat = ShaderReloadService.getStat(scope);
                lines.add(scope.label() + ": " + formatReloadStat(stat));
            }
        } catch (Exception exception) {
            lines.clear();
            lines.add(section("Debug"));
            lines.add("Debug info unavailable");
            lines.add(exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }

        return lines;
    }

    private static String section(String title) {
        return "[" + title + "]";
    }

    private static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    private static String formatFrameTime(int fps) {
        if (fps <= 0) {
            return "n/a";
        }
        return "%.2f ms/frame".formatted(1000.0 / fps);
    }

    private static String formatReloadStat(ShaderReloadService.ReloadStat stat) {
        if (stat.finishedAtMillis() <= 0L) {
            return stat.message();
        }
        String age = formatAge(System.currentTimeMillis() - stat.finishedAtMillis());
        String duration = stat.durationMillis() < 0L ? "n/a" : stat.durationMillis() + " ms";
        String status = stat.success() ? "OK" : "FAIL";
        return status + " | " + duration + " | " + age + " ago" + ("OK".equals(stat.message()) ? "" : " | " + stat.message());
    }

    private static String formatAge(long ageMillis) {
        if (ageMillis < 1000L) {
            return ageMillis + " ms";
        }
        Duration duration = Duration.ofMillis(ageMillis);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).toSeconds();
        if (minutes > 0) {
            return minutes + "m " + seconds + "s";
        }
        return seconds + "s";
    }

    private static String safeValue(DebugValueSupplier supplier) {
        try {
            return supplier.get();
        } catch (Exception exception) {
            return "n/a";
        }
    }

    @FunctionalInterface
    private interface DebugValueSupplier {
        String get() throws Exception;
    }
}
