package com.seailz.csdt.client.service;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;

import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Workaround for MC-307387: Vulkan rejects shader-declared uniforms when the
 * pipeline did not explicitly declare their bind group.
 */
public final class Mc307387FixService {

    private static final long FALLBACK_UNIFORM_BUFFER_SIZE = 65536L;

    private static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("csdt.fix.mc307387", "true"));
    private static GpuBuffer fallbackUniformBuffer;

    private Mc307387FixService() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean enabled) {
        Mc307387FixService.enabled = enabled;
    }

    public static boolean toggle() {
        enabled = !enabled;
        return enabled;
    }

    public static void addShaderDefinedResources(
            List<VulkanBindGroupLayout.Entry> entries,
            IntermediaryShaderModule module,
            RenderPipeline pipeline
    ) throws ShaderCompileException {
        try {
            Map<String, BindGroupLayout.UniformDescription> declaredUniforms = BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts()).stream()
                    .collect(Collectors.toMap(BindGroupLayout.UniformDescription::name, Function.identity(), (first, ignored) -> first));
            Set<String> declaredSamplers = Set.copyOf(BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts()));

            for (Object uniformBuffer : module.uniformBuffers()) {
                String name = stringRecordValue(uniformBuffer, "name");
                addEntryIfMissing(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER, name, null);
            }

            for (Object sampler : module.samplers()) {
                String name = stringRecordValue(sampler, "name");
                int dimensions = intRecordValue(sampler, "dimensions");
                BindGroupLayout.UniformDescription uniform = declaredUniforms.get(name);
                if (uniform != null) {
                    if (dimensions != 5) {
                        throw new ShaderCompileException("Uniform " + name + " should be a texel buffer but has incorrect dimensions");
                    }
                    addEntryIfMissing(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER, name, uniform.gpuFormat());
                    continue;
                }

                if (!declaredSamplers.contains(name) && dimensions == 5) {
                    throw new ShaderCompileException("Unable to infer texel buffer format for shader defined uniform (" + name + ")");
                }
                if (!declaredSamplers.contains(name)) {
                    throw new ShaderCompileException("Sampler " + name + " is not declared by pipeline " + pipeline.getLocation());
                }
                if (dimensions != 1 && dimensions != 3) {
                    throw new ShaderCompileException("Sampler " + name + " has incorrect dimensions");
                }
                addEntryIfMissing(entries, VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE, name, null);
            }
        } catch (ReflectiveOperationException exception) {
            throw new ShaderCompileException("Failed to inspect shader-defined uniforms: " + exception);
        }
    }

    public static void bindDefaultAndFallbackUniforms(Map<String, GpuBufferSlice> uniforms, VulkanBindGroupLayout layout) {
        putIfPresent(uniforms, "Projection", RenderSystem.getProjectionMatrixBuffer());
        putIfPresent(uniforms, "Fog", RenderSystem.getShaderFog());
        GpuBuffer globals = RenderSystem.getGlobalSettingsUniform();
        if (globals != null) {
            uniforms.putIfAbsent("Globals", globals.slice());
        }
        putIfPresent(uniforms, "Lighting", RenderSystem.getShaderLights());

        GpuBufferSlice fallback = null;
        for (VulkanBindGroupLayout.Entry entry : layout.entries()) {
            if (entry.type() != VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER || uniforms.containsKey(entry.name())) {
                continue;
            }
            if (fallback == null) {
                fallback = fallbackUniformSlice();
            }
            uniforms.put(entry.name(), fallback);
        }
    }

    private static void putIfPresent(Map<String, GpuBufferSlice> uniforms, String name, GpuBufferSlice value) {
        if (value != null) {
            uniforms.putIfAbsent(name, value);
        }
    }

    private static GpuBufferSlice fallbackUniformSlice() {
        if (fallbackUniformBuffer == null || fallbackUniformBuffer.isClosed()) {
            fallbackUniformBuffer = RenderSystem.getDevice().createBuffer(
                    () -> "CSDT Fallback Uniform Buffer",
                    GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_HINT_CLIENT_STORAGE | GpuBuffer.USAGE_UNIFORM,
                    FALLBACK_UNIFORM_BUFFER_SIZE
            );
            try (GpuBufferSlice.MappedView mapped = fallbackUniformBuffer.map(false, true)) {
                IntBuffer words = mapped.data().order(ByteOrder.nativeOrder()).asIntBuffer();
                for (int index = 0; index < words.limit(); index++) {
                    words.put(index, 0);
                }
            }
        }
        return fallbackUniformBuffer.slice();
    }

    private static void addEntryIfMissing(
            List<VulkanBindGroupLayout.Entry> entries,
            VulkanBindGroupLayout.VulkanBindGroupEntryType type,
            String name,
            GpuFormat gpuFormat
    ) {
        boolean exists = entries.stream()
                .anyMatch(entry -> entry.type() == type && entry.name().equals(name));
        if (!exists) {
            entries.add(new VulkanBindGroupLayout.Entry(type, name, gpuFormat));
        }
    }

    private static String stringRecordValue(Object value, String methodName) throws ReflectiveOperationException {
        return (String) recordValue(value, methodName);
    }

    private static int intRecordValue(Object value, String methodName) throws ReflectiveOperationException {
        return (Integer) recordValue(value, methodName);
    }

    private static Object recordValue(Object value, String methodName) throws ReflectiveOperationException {
        var method = value.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        return method.invoke(value);
    }
}
