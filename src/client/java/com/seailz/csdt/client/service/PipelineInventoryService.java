package com.seailz.csdt.client.service;

import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class PipelineInventoryService {

    private PipelineInventoryService() {
    }

    public static List<PipelineEntry> listPipelines() {
        return RenderPipelines.getStaticPipelines().stream()
                .sorted(Comparator.comparing(pipeline -> pipeline.getLocation().toString()))
                .map(PipelineEntry::new)
                .toList();
    }

    public static String describe(PipelineEntry entry) {
        RenderPipeline pipeline = entry.pipeline();
        return """
                [Shaders]
                Vertex: %s
                Fragment: %s

                [Geometry]
                Vertex Formats: %s
                Primitive Topology: %s
                Polygon Mode: %s

                [Resources]
                Samplers: %s
                Uniforms: %s
                Defines: %s

                [State]
                Cull: %s
                Wants Depth Texture: %s
                Color Target: %s
                Depth/Stencil: %s
                """.formatted(
                shortId(pipeline.getVertexShader()),
                shortId(pipeline.getFragmentShader()),
                Arrays.toString(pipeline.getVertexFormatBindings()),
                pipeline.getPrimitiveTopology(),
                pipeline.getPolygonMode(),
                formatList(BindGroupLayout.flattenSamplers(pipeline.getBindGroupLayouts())),
                formatList(BindGroupLayout.flattenUniforms(pipeline.getBindGroupLayouts())),
                pipeline.getShaderDefines(),
                pipeline.isCull() ? "Enabled" : "Disabled",
                pipeline.wantsDepthTexture() ? "Yes" : "No",
                pipeline.getColorTargetState(),
                pipeline.getDepthStencilState() == null ? "<none>" : pipeline.getDepthStencilState().toString()
        ).trim();
    }

    private static String formatList(List<?> values) {
        return values.isEmpty() ? "<none>" : values.toString();
    }

    private static String shortId(Object value) {
        String text = String.valueOf(value);
        return text.startsWith("minecraft:") ? text.substring("minecraft:".length()) : text;
    }

    public record PipelineEntry(RenderPipeline pipeline) {
        public String location() {
            return this.pipeline.getLocation().toString();
        }
    }
}
