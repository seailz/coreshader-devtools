package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.seailz.csdt.client.service.UniformInspectorService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(CommandEncoder.class)
public abstract class CommandEncoderMixin {

    @Inject(method = "writeToBuffer", at = @At("HEAD"))
    private void csdt$recordUniformInspectorBufferWrite(GpuBufferSlice slice, ByteBuffer source, CallbackInfo ci) {
        UniformInspectorService.recordBufferWrite(slice, source);
    }
}
