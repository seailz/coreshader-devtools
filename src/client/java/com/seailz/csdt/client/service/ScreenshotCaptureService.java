package com.seailz.csdt.client.service;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;

public final class ScreenshotCaptureService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneId.systemDefault());

    private ScreenshotCaptureService() {
    }

    public static CompletableFuture<Path> captureScreenshot() {
        CompletableFuture<Path> future = new CompletableFuture<>();
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> captureOnClientThread(minecraft, future));
        return future;
    }

    private static void captureOnClientThread(Minecraft minecraft, CompletableFuture<Path> future) {
        try {
            String fileName = "csdt-" + FILE_STAMP.format(Instant.now()) + ".png";
            Path screenshotPath = minecraft.gameDirectory.toPath()
                    .resolve("screenshots")
                    .resolve(fileName)
                    .toAbsolutePath()
                    .normalize();

            Screenshot.grab(
                    minecraft.gameDirectory,
                    fileName,
                    minecraft.gameRenderer.mainRenderTarget(),
                    1,
                    message -> {
                        try {
                            if (Files.exists(screenshotPath)) {
                                future.complete(screenshotPath);
                            } else {
                                future.completeExceptionally(new IllegalStateException("Screenshot was not written: " + message.getString()));
                            }
                        } catch (Exception exception) {
                            LOGGER.error("Failed to finish screenshot capture", exception);
                            future.completeExceptionally(exception);
                        }
                    }
            );
        } catch (Exception exception) {
            LOGGER.error("Failed to start screenshot capture", exception);
            future.completeExceptionally(exception);
        }
    }
}
