package com.seailz.csdt.client.service;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;

public final class SamplerInspectionService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BINDINGS = 160;
    private static final int READBACK_TIMEOUT_MILLIS = 250;

    private static final IdentityHashMap<RenderPass, PipelineContext> PIPELINES = new IdentityHashMap<>();
    private static final LinkedHashMap<String, BindingRecord> BINDINGS = new LinkedHashMap<>();
    private static long sequence;
    private static GpuSampler previewSampler;

    private SamplerInspectionService() {
    }

    public static synchronized void rememberPipeline(RenderPass pass, RenderPipeline pipeline) {
        if (pass == null || pipeline == null) {
            return;
        }
        PIPELINES.put(pass, new PipelineContext(
                shortId(pipeline.getLocation()),
                shortId(pipeline.getVertexShader()),
                shortId(pipeline.getFragmentShader())
        ));
    }

    public static synchronized void forgetRenderPass(RenderPass pass) {
        if (pass != null) {
            PIPELINES.remove(pass);
        }
    }

    public static synchronized void captureBinding(RenderPass pass, String samplerName, GpuTextureView view, GpuSampler sampler) {
        try {
            if (samplerName == null || view == null) {
                return;
            }
            GpuTexture texture = view.texture();
            if (texture == null) {
                return;
            }
            PipelineContext pipeline = PIPELINES.getOrDefault(pass, PipelineContext.UNKNOWN);
            int baseMipLevel = view.baseMipLevel();
            GpuFormat format = texture.getFormat();
            String id = pipeline.location() + "|" + samplerName + "|" + System.identityHashCode(texture) + "|" + baseMipLevel;
            BindingRecord record = new BindingRecord(
                    id,
                    samplerName,
                    pipeline,
                    safeLabel(texture),
                    texture.getWidth(baseMipLevel),
                    texture.getHeight(baseMipLevel),
                    texture.getDepthOrLayers(),
                    texture.getMipLevels(),
                    baseMipLevel,
                    view.mipLevels(),
                    format,
                    texture.usage(),
                    describeSampler(sampler),
                    new WeakReference<>(view),
                    new WeakReference<>(sampler),
                    new WeakReference<>(texture),
                    System.nanoTime(),
                    ++sequence
            );
            BINDINGS.remove(id);
            BINDINGS.put(id, record);
            while (BINDINGS.size() > MAX_BINDINGS) {
                String eldest = BINDINGS.keySet().iterator().next();
                BINDINGS.remove(eldest);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to capture sampler binding", exception);
        }
    }

    public static synchronized List<SamplerBindingSnapshot> snapshotBindings() {
        long now = System.nanoTime();
        return BINDINGS.values().stream()
                .map(record -> record.snapshot(now))
                .sorted(Comparator
                        .comparing(SamplerBindingSnapshot::lightmapLike).reversed()
                        .thenComparing(SamplerBindingSnapshot::sequence).reversed())
                .toList();
    }

    public static synchronized SamplerBindingSnapshot findSnapshot(String id) {
        BindingRecord record = BINDINGS.get(id);
        return record == null ? null : record.snapshot(System.nanoTime());
    }

    public static synchronized PreviewBinding previewBinding(String id, boolean forceNearest) {
        BindingRecord record = BINDINGS.get(id);
        if (record == null) {
            return null;
        }
        GpuTextureView view = record.viewReference().get();
        GpuSampler sampler = record.samplerReference().get();
        GpuTexture texture = record.textureReference().get();
        if (view == null || sampler == null || texture == null || view.isClosed() || texture.isClosed()) {
            return null;
        }
        if (!forceNearest) {
            return new PreviewBinding(view, sampler);
        }
        GpuSampler nearestSampler = nearestPreviewSampler();
        return nearestSampler == null ? null : new PreviewBinding(view, nearestSampler);
    }

    public static ReadbackResult readPixel(String id, int x, int y) {
        return readRegion(id, x, y, 1, 1);
    }

    public static ReadbackResult readRegion(String id, int x, int y, int requestedWidth, int requestedHeight) {
        BindingRecord record = binding(id);
        if (record == null) {
            return ReadbackResult.failure("Sampler binding is no longer available");
        }
        GpuTexture texture = record.textureReference().get();
        if (texture == null) {
            return ReadbackResult.failure("Texture has been released");
        }
        if (texture.isClosed()) {
            return ReadbackResult.failure("Texture is closed");
        }
        if (!RenderSystem.isOnRenderThread()) {
            return ReadbackResult.failure("Readback must run on the render thread");
        }

        int mip = record.baseMipLevel();
        int textureWidth = texture.getWidth(mip);
        int textureHeight = texture.getHeight(mip);
        int safeX = Math.clamp(x, 0, Math.max(0, textureWidth - 1));
        int safeY = Math.clamp(y, 0, Math.max(0, textureHeight - 1));
        int width = Math.clamp(requestedWidth, 1, textureWidth - safeX);
        int height = Math.clamp(requestedHeight, 1, textureHeight - safeY);
        int pixelSize = Math.max(1, texture.getFormat().pixelSize());
        int byteCount = Math.multiplyExact(Math.multiplyExact(width, height), pixelSize);
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return ReadbackResult.failure("GPU device is unavailable");
        }

        GpuBuffer buffer = null;
        try {
            buffer = device.createBuffer(
                    () -> "CSDT sampler readback",
                    GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_HINT_CLIENT_STORAGE,
                    byteCount
            );
            CommandEncoder encoder = device.createCommandEncoder();
            encoder.copyTextureToBuffer(texture, buffer, 0L, () -> {
            }, mip, safeX, safeY, width, height);
            try (GpuFence fence = encoder.createFence()) {
                encoder.submit();
                if (!fence.awaitCompletion(READBACK_TIMEOUT_MILLIS)) {
                    return ReadbackResult.failure("Timed out waiting for GPU readback");
                }
            }

            byte[] bytes = new byte[byteCount];
            try (GpuBufferSlice.MappedView mapped = buffer.map(true, false)) {
                ByteBuffer data = mapped.data();
                data.position(0);
                data.get(bytes, 0, Math.min(bytes.length, data.remaining()));
            }
            return ReadbackResult.success(record.snapshot(System.nanoTime()), safeX, safeY, width, height, pixelSize, bytes);
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to read sampler texture", exception);
            return ReadbackResult.failure(exception.toString());
        } finally {
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    private static synchronized BindingRecord binding(String id) {
        return BINDINGS.get(id);
    }

    private static String describeSampler(GpuSampler sampler) {
        if (sampler == null) {
            return "<unknown sampler state>";
        }
        try {
            String lod = sampler.getMaxLod().isPresent() ? String.format(Locale.ROOT, "%.2f", sampler.getMaxLod().getAsDouble()) : "unbounded";
            return "wrap=%s/%s min=%s mag=%s aniso=%d lod=%s".formatted(
                    sampler.getAddressModeU(),
                    sampler.getAddressModeV(),
                    sampler.getMinFilter(),
                    sampler.getMagFilter(),
                    sampler.getMaxAnisotropy(),
                    lod
            );
        } catch (RuntimeException exception) {
            LOGGER.error("Failed to describe sampler state", exception);
            return "<sampler state unavailable>";
        }
    }

    private static GpuSampler nearestPreviewSampler() {
        if (previewSampler != null) {
            return previewSampler;
        }
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        previewSampler = device.createSampler(
                AddressMode.CLAMP_TO_EDGE,
                AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST,
                FilterMode.NEAREST,
                1,
                OptionalDouble.empty()
        );
        return previewSampler;
    }

    private static String safeLabel(GpuTexture texture) {
        String label = texture.getLabel();
        return label == null || label.isBlank() ? "<unnamed>" : label;
    }

    private static String shortId(Object value) {
        String text = String.valueOf(value);
        return text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
    }

    private record PipelineContext(String location, String vertexShader, String fragmentShader) {
        private static final PipelineContext UNKNOWN = new PipelineContext("<unknown pipeline>", "<unknown vertex>", "<unknown fragment>");
    }

    private record BindingRecord(
            String id,
            String samplerName,
            PipelineContext pipeline,
            String textureLabel,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels,
            int baseMipLevel,
            int viewMipLevels,
            GpuFormat format,
            int usage,
            String samplerState,
            WeakReference<GpuTextureView> viewReference,
            WeakReference<GpuSampler> samplerReference,
            WeakReference<GpuTexture> textureReference,
            long lastBoundNanos,
            long sequence
    ) {
        private SamplerBindingSnapshot snapshot(long nowNanos) {
            boolean closed = false;
            GpuTexture texture = this.textureReference.get();
            if (texture != null) {
                closed = texture.isClosed();
            }
            return new SamplerBindingSnapshot(
                    this.id,
                    this.samplerName,
                    this.pipeline.location(),
                    this.pipeline.vertexShader(),
                    this.pipeline.fragmentShader(),
                    this.textureLabel,
                    this.width,
                    this.height,
                    this.depthOrLayers,
                    this.mipLevels,
                    this.baseMipLevel,
                    this.viewMipLevels,
                    this.format.toString(),
                    this.format.pixelSize(),
                    this.usage,
                    (this.usage & GpuTexture.USAGE_COPY_SRC) != 0,
                    this.samplerState,
                    closed,
                    Duration.ofNanos(Math.max(0L, nowNanos - this.lastBoundNanos)).toMillis(),
                    this.sequence,
                    lightmapLike(this.samplerName, this.textureLabel)
            );
        }
    }

    public record PreviewBinding(GpuTextureView view, GpuSampler sampler) {
    }

    public record SamplerBindingSnapshot(
            String id,
            String samplerName,
            String pipelineLocation,
            String vertexShader,
            String fragmentShader,
            String textureLabel,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels,
            int baseMipLevel,
            int viewMipLevels,
            String format,
            int pixelSize,
            int usage,
            boolean copySrc,
            String samplerState,
            boolean textureClosed,
            long ageMillis,
            long sequence,
            boolean lightmapLike
    ) {
        public String shortTitle() {
            if (Objects.equals(this.textureLabel, "<unnamed>")) {
                return this.samplerName;
            }
            return this.samplerName + " | " + this.textureLabel;
        }
    }

    public record ReadbackResult(boolean success, String message, List<String> displayLines, String dumpText) {
        private static ReadbackResult failure(String message) {
            return new ReadbackResult(false, message, List.of(message), message);
        }

        private static ReadbackResult success(SamplerBindingSnapshot binding, int x, int y, int width, int height, int pixelSize, byte[] bytes) {
            List<String> lines = new ArrayList<>();
            StringBuilder dump = new StringBuilder();
            appendLine(lines, dump, "Sampler: " + binding.shortTitle());
            appendLine(lines, dump, "Pipeline: " + binding.pipelineLocation());
            appendLine(lines, dump, "Texture: %s %dx%d %s usage=0x%X".formatted(binding.textureLabel(), binding.width(), binding.height(), binding.format(), binding.usage()));
            appendLine(lines, dump, "Region: x=%d y=%d w=%d h=%d pixelSize=%d".formatted(x, y, width, height, pixelSize));
            for (int row = 0; row < height; row++) {
                StringBuilder rowBuilder = new StringBuilder("y=%02d:".formatted(y + row));
                for (int col = 0; col < width; col++) {
                    int offset = (row * width + col) * pixelSize;
                    rowBuilder.append(' ').append(formatPixel(bytes, offset, pixelSize));
                }
                appendLine(lines, dump, rowBuilder.toString());
            }
            return new ReadbackResult(true, "Read %d pixel(s)".formatted(width * height), lines, dump.toString());
        }

        private static void appendLine(List<String> lines, StringBuilder dump, String line) {
            lines.add(line);
            dump.append(line).append('\n');
        }

        private static String formatPixel(byte[] bytes, int offset, int pixelSize) {
            StringBuilder raw = new StringBuilder();
            raw.append('[');
            for (int i = 0; i < pixelSize; i++) {
                if (i > 0) {
                    raw.append(',');
                }
                raw.append(Byte.toUnsignedInt(bytes[offset + i]));
            }
            raw.append(']');
            if (pixelSize == 4) {
                return "#%02X%02X%02X%02X%s".formatted(
                        Byte.toUnsignedInt(bytes[offset]),
                        Byte.toUnsignedInt(bytes[offset + 1]),
                        Byte.toUnsignedInt(bytes[offset + 2]),
                        Byte.toUnsignedInt(bytes[offset + 3]),
                        raw
                );
            }
            return raw.toString();
        }
    }

    private static boolean lightmapLike(String samplerName, String textureLabel) {
        String sampler = samplerName == null ? "" : samplerName.toLowerCase(Locale.ROOT);
        String label = textureLabel == null ? "" : textureLabel.toLowerCase(Locale.ROOT);
        return sampler.contains("lightmap") || label.contains("lightmap") || sampler.equals("sampler2");
    }
}
