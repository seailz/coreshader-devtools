package com.seailz.csdt.client.service;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import com.seailz.csdt.client.mixins.GlBufferAccessor;
import org.lwjgl.opengl.ARBShaderStorageBufferObject;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShaderDebugRuntimeService {

    public static final int VULKAN_STORAGE_USAGE = 1 << 30;
    private static final long POLL_INTERVAL_NANOS = 100_000_000L;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<Integer, SiteSnapshot> SNAPSHOTS = new HashMap<>();

    private static GpuBuffer storageBuffer;
    private static int bufferGeneration = -1;
    private static int bufferSlotCapacity = -1;
    private static boolean readbackQueued;
    private static boolean readbackReady;
    private static long nextPollTimeNanos;

    private ShaderDebugRuntimeService() {
    }

    public static synchronized void bindStorageBuffer() {
        if (!RenderSystem.isOnRenderThread() || !isOpenGlBackend()) {
            return;
        }
        ensureStorageBuffer();
        if (storageBuffer == null) {
            return;
        }
        GL30.glBindBufferBase(
                ARBShaderStorageBufferObject.GL_SHADER_STORAGE_BUFFER,
                ShaderDebugSourceService.STORAGE_BINDING,
                ((GlBufferAccessor) storageBuffer).csdt$getHandle()
        );
    }

    public static synchronized void pollAndLogChanges() {
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        ensureStorageBuffer();
        if (storageBuffer == null) {
            return;
        }
        if (readbackReady) {
            readbackReady = false;
            readAndLogMessages();
            nextPollTimeNanos = System.nanoTime() + POLL_INTERVAL_NANOS;
        }
        if (!readbackQueued && System.nanoTime() >= nextPollTimeNanos) {
            readbackQueued = true;
            RenderSystem.queueFencedTask(() -> {
                synchronized (ShaderDebugRuntimeService.class) {
                    readbackQueued = false;
                    readbackReady = true;
                }
            });
        }
    }

    public static synchronized GpuBufferSlice storageSlice() {
        ensureStorageBuffer();
        return storageBuffer == null ? null : storageBuffer.slice();
    }

    private static void readAndLogMessages() {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mapped = encoder.mapBuffer(storageBuffer, true, true)) {
            IntBuffer words = mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer();
            List<ShaderDebugSourceService.DebugSite> sites = ShaderDebugSourceService.snapshotSites();
            for (ShaderDebugSourceService.DebugSite site : sites) {
                int base = site.id() * ShaderDebugSourceService.SLOT_WORDS;
                if (base + ShaderDebugSourceService.SLOT_WORDS > words.limit()) {
                    continue;
                }
                int version = words.get(base);
                int claim = words.get(base + 1);
                if (claim == 0 || version == 0) {
                    continue;
                }
                int length = Math.max(0, Math.min(words.get(base + 2), ShaderDebugSourceService.MAX_MESSAGE_LENGTH));
                String message = readMessage(words, base + 3, length);
                SiteSnapshot previous = SNAPSHOTS.get(site.id());
                if (previous != null && previous.version == version) {
                    continue;
                }
                SNAPSHOTS.put(site.id(), new SiteSnapshot(version, message));
                if (previous != null && previous.message.equals(message)) {
                    continue;
                }
                LOGGER.info("Shader dbg [{} {}:{}] {}", site.type().getName(), site.location(), site.line(), sanitize(message));
            }
            for (int slot = 0; slot < bufferSlotCapacity; slot++) {
                int claimOffset = slot * ShaderDebugSourceService.SLOT_WORDS + 1;
                if (claimOffset < words.limit()) {
                    words.put(claimOffset, 0);
                }
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to read shader debug buffer", exception);
        }
    }

    private static String readMessage(IntBuffer words, int start, int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int index = 0; index < length; index++) {
            builder.append((char) words.get(start + index));
        }
        return builder.toString();
    }

    private static String sanitize(String message) {
        return message
                .replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    private static void ensureStorageBuffer() {
        int generation = ShaderDebugSourceService.generation();
        int slotCapacity = ShaderDebugSourceService.slotCapacity();
        if (generation == bufferGeneration && slotCapacity == bufferSlotCapacity) {
            return;
        }
        closeStorageBuffer();
        SNAPSHOTS.clear();
        readbackQueued = false;
        readbackReady = false;
        nextPollTimeNanos = 0L;
        bufferGeneration = generation;
        bufferSlotCapacity = slotCapacity;
        if (slotCapacity <= 0) {
            return;
        }

        long size = (long) slotCapacity * ShaderDebugSourceService.SLOT_WORDS * Integer.BYTES;
        storageBuffer = RenderSystem.getDevice().createBuffer(
                () -> "CSDT Shader Debug Buffer",
                GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_UNIFORM | VULKAN_STORAGE_USAGE,
                size
        );
        try (GpuBuffer.MappedView mapped = RenderSystem.getDevice().createCommandEncoder().mapBuffer(storageBuffer, false, true)) {
            IntBuffer words = mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer();
            for (int index = 0; index < words.limit(); index++) {
                words.put(index, 0);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to initialize shader debug buffer", exception);
            closeStorageBuffer();
        }
    }

    private static void closeStorageBuffer() {
        if (storageBuffer != null) {
            storageBuffer.close();
            storageBuffer = null;
        }
    }

    private static boolean isOpenGlBackend() {
        GpuDevice device = RenderSystem.tryGetDevice();
        return device != null && "opengl".equals(device.getDeviceInfo().backendName().toLowerCase(Locale.ROOT));
    }

    private record SiteSnapshot(int version, String message) {
    }
}
