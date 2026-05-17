package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.seailz.csdt.client.service.UniformInspectorService;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {

    @Shadow
    @Final
    private MappableRingBuffer regularBuffer;

    @Inject(method = "updateBuffer(Lnet/minecraft/client/renderer/fog/FogData;)V", at = @At("TAIL"))
    private void csdt$recordUniformInspectorFogWrite(FogData fogData, CallbackInfo ci) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(FogRenderer.FOG_UBO_SIZE).order(ByteOrder.nativeOrder());
        Std140Builder.intoBuffer(buffer)
                .putVec4(fogData.color)
                .putFloat(fogData.environmentalStart)
                .putFloat(fogData.renderDistanceStart)
                .putFloat(fogData.environmentalEnd)
                .putFloat(fogData.renderDistanceEnd)
                .putFloat(fogData.skyEnd)
                .putFloat(fogData.cloudEnd);
        buffer.position(0);
        buffer.limit(buffer.capacity());
        UniformInspectorService.recordBufferWrite(
                this.regularBuffer.currentBuffer().slice(0L, FogRenderer.FOG_UBO_SIZE),
                buffer
        );
    }
}
