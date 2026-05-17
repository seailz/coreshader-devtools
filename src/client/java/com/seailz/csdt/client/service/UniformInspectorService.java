package com.seailz.csdt.client.service;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import org.jetbrains.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UniformInspectorService {

    private static final int MAX_CAPTURE_BYTES = 8192;
    private static final int MAX_UNIFORM_ENTRIES = 512;
    private static final int MAX_VALUE_HISTORY_ENTRIES = 4096;
    private static final int MAX_VALUE_HISTORY_SAMPLES = 20;
    private static final Map<SliceKey, BufferSnapshot> BUFFER_SNAPSHOTS = new HashMap<>();
    private static final LinkedHashMap<UniformKey, UniformSnapshot> UNIFORM_SNAPSHOTS = new LinkedHashMap<>();
    private static final LinkedHashMap<ValueHistoryKey, ValueHistory> VALUE_HISTORIES = new LinkedHashMap<>();

    private static long sequence;

    private UniformInspectorService() {
    }

    public static synchronized void recordBufferWrite(@Nullable GpuBufferSlice slice, @Nullable ByteBuffer source) {
        if (slice == null || source == null || slice.buffer().isClosed()) {
            return;
        }

        ByteBuffer readable = source.asReadOnlyBuffer();
        int totalBytes = readable.remaining();
        int capturedBytes = Math.min(totalBytes, MAX_CAPTURE_BYTES);
        byte[] bytes = new byte[capturedBytes];
        readable.get(bytes);
        BUFFER_SNAPSHOTS.put(SliceKey.from(slice), new BufferSnapshot(bytes, totalBytes, System.currentTimeMillis(), sequence++));
    }

    public static synchronized void recordCreatedBuffer(@Nullable GpuBuffer buffer, @Nullable ByteBuffer source) {
        if (buffer == null || source == null || buffer.isClosed()) {
            return;
        }
        long length = Math.min(buffer.size(), source.remaining());
        if (length <= 0L || length > Integer.MAX_VALUE) {
            return;
        }
        recordBufferWrite(buffer.slice(0L, length), source);
    }

    public static synchronized void recordUniformBinding(String backend, @Nullable RenderPipeline pipeline, String name, @Nullable GpuBuffer buffer) {
        if (buffer == null || buffer.isClosed()) {
            return;
        }
        recordUniformBinding(backend, pipeline, name, buffer.slice());
    }

    public static synchronized void recordUniformBinding(String backend, @Nullable RenderPipeline pipeline, String name, @Nullable GpuBufferSlice slice) {
        if (name == null || name.isBlank() || slice == null || slice.buffer().isClosed()) {
            return;
        }

        String pipelineLocation = pipeline == null ? "<pipeline unset>" : pipeline.getLocation().toString();
        UniformMetadata metadata = metadataFor(pipeline, name);
        BufferSnapshot bufferSnapshot = BUFFER_SNAPSHOTS.get(SliceKey.from(slice));
        UniformKey key = new UniformKey(backend, pipelineLocation, name);
        DecodedValue decodedValue = bufferSnapshot == null ? DecodedValue.uncaptured() : decodeValue(name, bufferSnapshot);
        if (bufferSnapshot != null) {
            recordValueHistories(key.stableKey(), decodedValue.lines(), bufferSnapshot.sequence(), System.currentTimeMillis());
        }
        UniformSnapshot uniformSnapshot = new UniformSnapshot(
                key.stableKey(),
                name,
                backend,
                pipelineLocation,
                pipeline == null ? "<unknown>" : shortId(pipeline.getVertexShader()),
                pipeline == null ? "<unknown>" : shortId(pipeline.getFragmentShader()),
                metadata.type(),
                metadata.gpuFormat(),
                slice.offset(),
                slice.length(),
                bufferSnapshot == null ? 0 : bufferSnapshot.bytes().length,
                bufferSnapshot == null ? 0 : bufferSnapshot.totalBytes(),
                bufferSnapshot == null || bufferSnapshot.bytes().length < bufferSnapshot.totalBytes(),
                bufferSnapshot != null,
                decodedValue.lines(),
                System.currentTimeMillis(),
                sequence++
        );

        UNIFORM_SNAPSHOTS.remove(key);
        UNIFORM_SNAPSHOTS.put(key, uniformSnapshot);
        while (UNIFORM_SNAPSHOTS.size() > MAX_UNIFORM_ENTRIES) {
            UniformKey oldest = UNIFORM_SNAPSHOTS.keySet().iterator().next();
            UNIFORM_SNAPSHOTS.remove(oldest);
        }
    }

    public static synchronized List<UniformSnapshot> snapshotUniforms() {
        return UNIFORM_SNAPSHOTS.values().stream()
                .sorted(Comparator.comparing(UniformSnapshot::name)
                        .thenComparing(UniformSnapshot::shortPipeline)
                        .thenComparing(UniformSnapshot::backend))
                .toList();
    }

    public static synchronized List<ValueSample> valueHistory(String uniformKey, String valueKey) {
        ValueHistory history = VALUE_HISTORIES.get(new ValueHistoryKey(uniformKey, valueKey));
        return history == null ? List.of() : List.copyOf(history.samples());
    }

    private static UniformMetadata metadataFor(@Nullable RenderPipeline pipeline, String name) {
        if (pipeline == null) {
            return UniformMetadata.UNKNOWN;
        }
        for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())) {
            if (uniform.name().equals(name)) {
                return new UniformMetadata(
                        String.valueOf(uniform.type()),
                        uniform.gpuFormat() == null ? "<none>" : String.valueOf(uniform.gpuFormat())
                );
            }
        }
        return UniformMetadata.UNKNOWN;
    }

    private static DecodedValue decodeValue(String name, BufferSnapshot snapshot) {
        byte[] bytes = snapshot.bytes();
        List<ValueLine> lines = new ArrayList<>();
        switch (name) {
            case "Projection" -> decodeMatrix(lines, "Projection", bytes, 0);
            case "Globals" -> decodeGlobals(lines, bytes);
            case "Fog" -> decodeFog(lines, bytes);
            case "DynamicTransforms" -> decodeDynamicTransforms(lines, bytes);
            case "ChunkSection" -> decodeChunkSection(lines, bytes);
            default -> {
            }
        }

        if (!lines.isEmpty()) {
            lines.add(ValueLine.separator());
        }

        if (snapshot.bytes().length < snapshot.totalBytes()) {
            lines.add(ValueLine.untracked("Captured first " + snapshot.bytes().length + " of " + snapshot.totalBytes() + " bytes"));
        } else {
            lines.add(ValueLine.untracked("Captured " + snapshot.totalBytes() + " bytes"));
        }
        return new DecodedValue(List.copyOf(lines));
    }

    private static void decodeGlobals(List<ValueLine> lines, byte[] bytes) {
        if (bytes.length < 56) {
            return;
        }
        lines.add(ValueLine.header("Globals"));
        lines.add(ValueLine.value("globals.camera-block", "Camera block", intAt(bytes, 0) + ", " + intAt(bytes, 4) + ", " + intAt(bytes, 8)));
        lines.add(ValueLine.value("globals.camera-local", "Camera local", floatAt(bytes, 16) + ", " + floatAt(bytes, 20) + ", " + floatAt(bytes, 24)));
        lines.add(ValueLine.value("globals.screen", "Screen", floatAt(bytes, 32) + " x " + floatAt(bytes, 36)));
        lines.add(ValueLine.value("globals.glint-alpha", "Glint alpha", floatAt(bytes, 40)));
        float gameTimeFraction = floatAtRaw(bytes, 44);
        lines.add(ValueLine.value("globals.gametime-fraction", "GameTime fraction", formatFloat(gameTimeFraction)));
        lines.add(ValueLine.value("globals.gametime-ticks", "GameTime ticks", formatFloat(gameTimeFraction * 24000.0F)));
        lines.add(ValueLine.value("globals.menu-blur-radius", "Menu blur radius", String.valueOf(intAt(bytes, 48))));
        lines.add(ValueLine.value("globals.use-rgss", "Use RGSS", String.valueOf(intAt(bytes, 52))));
    }

    private static void decodeFog(List<ValueLine> lines, byte[] bytes) {
        if (bytes.length < 40) {
            return;
        }
        lines.add(ValueLine.header("Fog"));
        lines.add(ValueLine.value("fog.color", "Color", floatAt(bytes, 0) + ", " + floatAt(bytes, 4) + ", " + floatAt(bytes, 8) + ", " + floatAt(bytes, 12)));
        lines.add(ValueLine.value("fog.environmental", "Environmental", floatAt(bytes, 16) + " -> " + floatAt(bytes, 24)));
        lines.add(ValueLine.value("fog.render-distance", "Render distance", floatAt(bytes, 20) + " -> " + floatAt(bytes, 28)));
        lines.add(ValueLine.value("fog.sky-end", "Sky end", floatAt(bytes, 32)));
        lines.add(ValueLine.value("fog.cloud-end", "Cloud end", floatAt(bytes, 36)));
    }

    private static void decodeDynamicTransforms(List<ValueLine> lines, byte[] bytes) {
        if (bytes.length < 160) {
            return;
        }
        lines.add(ValueLine.header("DynamicTransforms"));
        decodeMatrix(lines, "ModelView", bytes, 0);
        lines.add(ValueLine.value("dynamic-transforms.color-modulator", "Color modulator", floatAt(bytes, 64) + ", " + floatAt(bytes, 68) + ", " + floatAt(bytes, 72) + ", " + floatAt(bytes, 76)));
        lines.add(ValueLine.value("dynamic-transforms.model-offset", "Model offset", floatAt(bytes, 80) + ", " + floatAt(bytes, 84) + ", " + floatAt(bytes, 88)));
        decodeMatrix(lines, "Texture matrix", bytes, 96);
    }

    private static void decodeChunkSection(List<ValueLine> lines, byte[] bytes) {
        if (bytes.length < 96) {
            return;
        }
        lines.add(ValueLine.header("ChunkSection"));
        decodeMatrix(lines, "ModelView", bytes, 0);
        lines.add(ValueLine.value("chunk-section.visibility", "Visibility", floatAt(bytes, 64)));
        lines.add(ValueLine.value("chunk-section.texture-atlas", "Texture atlas", intAt(bytes, 72) + " x " + intAt(bytes, 76)));
        lines.add(ValueLine.value("chunk-section.section", "Section", intAt(bytes, 80) + ", " + intAt(bytes, 84) + ", " + intAt(bytes, 88)));
    }

    private static void decodeMatrix(List<ValueLine> lines, String label, byte[] bytes, int offset) {
        if (bytes.length < offset + 64) {
            return;
        }
        lines.add(ValueLine.header(label));
        String keyPrefix = valueKey(label);
        for (int row = 0; row < 4; row++) {
            int base = offset + row * 16;
            lines.add(ValueLine.value(keyPrefix + ".row-" + row, "  row " + row, floatAt(bytes, base) + "  " + floatAt(bytes, base + 4) + "  " + floatAt(bytes, base + 8) + "  " + floatAt(bytes, base + 12)));
        }
    }

    private static void recordValueHistories(String uniformKey, List<ValueLine> lines, long sourceSequence, long updatedAtMillis) {
        for (ValueLine line : lines) {
            if (!line.expandable()) {
                continue;
            }

            ValueHistoryKey key = new ValueHistoryKey(uniformKey, line.key());
            ValueHistory history = VALUE_HISTORIES.computeIfAbsent(key, ignored -> new ValueHistory());
            if (history.lastSourceSequence() == sourceSequence) {
                continue;
            }
            history.setLastSourceSequence(sourceSequence);
            history.samples().addFirst(new ValueSample(line.value(), updatedAtMillis));
            while (history.samples().size() > MAX_VALUE_HISTORY_SAMPLES) {
                history.samples().removeLast();
            }
        }

        while (VALUE_HISTORIES.size() > MAX_VALUE_HISTORY_ENTRIES) {
            ValueHistoryKey oldest = VALUE_HISTORIES.keySet().iterator().next();
            VALUE_HISTORIES.remove(oldest);
        }
    }

    private static String valueKey(String label) {
        return label.toLowerCase(Locale.ROOT)
                .replace(' ', '-')
                .replace(':', '-');
    }

    private static String shortId(Object value) {
        String text = String.valueOf(value);
        return text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
    }

    private static int intAt(byte[] bytes, int offset) {
        return offset + Integer.BYTES <= bytes.length ? intAtRaw(bytes, offset) : 0;
    }

    private static int intAtRaw(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Integer.BYTES).order(ByteOrder.nativeOrder()).getInt();
    }

    private static String floatAt(byte[] bytes, int offset) {
        if (offset + Float.BYTES > bytes.length) {
            return "n/a";
        }
        return formatFloat(floatAtRaw(bytes, offset));
    }

    private static float floatAtRaw(byte[] bytes, int offset) {
        return ByteBuffer.wrap(bytes, offset, Float.BYTES).order(ByteOrder.nativeOrder()).getFloat();
    }

    private static String formatFloat(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return Float.toString(value);
        }
        return String.format(Locale.ROOT, "%.6g", value);
    }

    public record UniformSnapshot(
            String key,
            String name,
            String backend,
            String pipeline,
            String vertexShader,
            String fragmentShader,
            String type,
            String gpuFormat,
            long offset,
            long length,
            int capturedBytes,
            int totalBytes,
            boolean truncated,
            boolean valueAvailable,
            List<ValueLine> valueLines,
            long updatedAtMillis,
            long sequence
    ) {
        public String subtitle() {
            return this.backend + " | " + shortPipeline() + " | " + this.type + " | " + this.length + " bytes";
        }

        public String shortPipeline() {
            return this.pipeline.startsWith("minecraft:") ? this.pipeline.substring("minecraft:".length()) : this.pipeline;
        }
    }

    public record ValueLine(String key, String label, String value, boolean header) {
        private static ValueLine header(String label) {
            return new ValueLine("", label, "", true);
        }

        private static ValueLine value(String key, String label, String value) {
            return new ValueLine(key, label, value, false);
        }

        private static ValueLine untracked(String label) {
            return new ValueLine("", label, "", false);
        }

        private static ValueLine separator() {
            return new ValueLine("", "", "", false);
        }

        public boolean expandable() {
            return !this.header && !this.key.isBlank() && !this.value.isBlank();
        }

        public String text() {
            return this.value.isEmpty() ? this.label : this.label + ": " + this.value;
        }
    }

    public record ValueSample(String value, long updatedAtMillis) {
    }

    private record DecodedValue(List<ValueLine> lines) {
        private static DecodedValue uncaptured() {
            return new DecodedValue(List.of(ValueLine.untracked("Value: <not captured>")));
        }
    }

    private record UniformMetadata(String type, String gpuFormat) {
        private static final UniformMetadata UNKNOWN = new UniformMetadata("<unknown>", "<unknown>");
    }

    private record BufferSnapshot(byte[] bytes, int totalBytes, long updatedAtMillis, long sequence) {
    }

    private record UniformKey(String backend, String pipeline, String name) {
        private String stableKey() {
            return this.backend + "|" + this.pipeline + "|" + this.name;
        }
    }

    private record ValueHistoryKey(String uniformKey, String valueKey) {
    }

    private static final class ValueHistory {
        private final java.util.ArrayDeque<ValueSample> samples = new java.util.ArrayDeque<>();
        private long lastSourceSequence = Long.MIN_VALUE;

        private java.util.ArrayDeque<ValueSample> samples() {
            return this.samples;
        }

        private long lastSourceSequence() {
            return this.lastSourceSequence;
        }

        private void setLastSourceSequence(long lastSourceSequence) {
            this.lastSourceSequence = lastSourceSequence;
        }
    }

    private record SliceKey(GpuBuffer buffer, long offset, long length) {
        private static SliceKey from(GpuBufferSlice slice) {
            return new SliceKey(slice.buffer(), slice.offset(), slice.length());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SliceKey key)) {
                return false;
            }
            return this.buffer == key.buffer && this.offset == key.offset && this.length == key.length;
        }

        @Override
        public int hashCode() {
            int result = System.identityHashCode(this.buffer);
            result = 31 * result + Long.hashCode(this.offset);
            result = 31 * result + Long.hashCode(this.length);
            return result;
        }
    }
}
