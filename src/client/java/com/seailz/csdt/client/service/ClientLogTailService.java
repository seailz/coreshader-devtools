package com.seailz.csdt.client.service;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

public final class ClientLogTailService {

    public static final int MAX_LINES = 200;

    private static final Path LATEST_LOG = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("latest.log");

    private static Snapshot snapshot = new Snapshot(LATEST_LOG, List.of(), "No log data loaded yet", false);
    private static long lastRefreshNanos;
    private static long lastSize = -1L;
    private static FileTime lastModifiedTime;

    private ClientLogTailService() {
    }

    public static synchronized Snapshot snapshot() {
        return snapshot;
    }

    public static synchronized Snapshot refresh(boolean force) {
        if (!force && !hasChanged()) {
            return snapshot;
        }

        if (!Files.exists(LATEST_LOG)) {
            snapshot = new Snapshot(LATEST_LOG, List.of(), "latest.log not found yet", true);
            lastSize = -1L;
            lastModifiedTime = null;
            lastRefreshNanos = System.nanoTime();
            return snapshot;
        }

        try {
            List<String> lines = Files.readAllLines(LATEST_LOG, StandardCharsets.UTF_8);
            snapshot = new Snapshot(
                    LATEST_LOG,
                    tail(lines, MAX_LINES),
                    "Showing %d most recent line%s".formatted(Math.min(MAX_LINES, lines.size()), lines.size() == 1 ? "" : "s"),
                    true
            );
            lastSize = Files.size(LATEST_LOG);
            lastModifiedTime = Files.getLastModifiedTime(LATEST_LOG);
        } catch (IOException exception) {
            snapshot = new Snapshot(LATEST_LOG, List.of(), "Failed to read latest.log: " + exception.getMessage(), false);
            lastSize = -1L;
            lastModifiedTime = null;
        }

        lastRefreshNanos = System.nanoTime();
        return snapshot;
    }

    public static synchronized boolean shouldRefresh(long intervalNanos) {
        return System.nanoTime() - lastRefreshNanos >= intervalNanos;
    }

    private static boolean hasChanged() {
        try {
            long size = Files.exists(LATEST_LOG) ? Files.size(LATEST_LOG) : -1L;
            FileTime modifiedTime = Files.exists(LATEST_LOG) ? Files.getLastModifiedTime(LATEST_LOG) : null;
            return size != lastSize || (modifiedTime != null ? !modifiedTime.equals(lastModifiedTime) : lastModifiedTime != null);
        } catch (IOException exception) {
            return true;
        }
    }

    private static List<String> tail(List<String> lines, int maxLines) {
        if (lines.size() <= maxLines) {
            return List.copyOf(lines);
        }
        return List.copyOf(new ArrayList<>(lines.subList(lines.size() - maxLines, lines.size())));
    }

    public record Snapshot(Path path, List<String> lines, String status, boolean readable) {
    }
}
