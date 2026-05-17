package com.seailz.csdt.client.mixins;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.seailz.csdt.client.service.UniformInspectorService;
import net.minecraft.client.renderer.DynamicUniformStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(DynamicUniformStorage.class)
public abstract class DynamicUniformStorageMixin {

    private static final ThreadLocal<ByteBuffer> CSDT_SCRATCH_BUFFER = ThreadLocal.withInitial(() -> ByteBuffer.allocateDirect(0));

    @Shadow
    @Final
    private int blockSize;

    @Inject(method = "writeUniform", at = @At("RETURN"))
    private void csdt$recordUniformInspectorDynamicWrite(
            DynamicUniformStorage.DynamicUniform uniform,
            CallbackInfoReturnable<GpuBufferSlice> cir
    ) {
        GpuBufferSlice slice = cir.getReturnValue();
        if (slice == null || uniform == null || this.blockSize <= 0) {
            return;
        }

        ByteBuffer buffer = scratchBuffer(this.blockSize);
        uniform.write(buffer);
        buffer.position(0);
        buffer.limit(this.blockSize);
        UniformInspectorService.recordBufferWrite(slice, buffer);
    }

    private static ByteBuffer scratchBuffer(int size) {
        ByteBuffer buffer = CSDT_SCRATCH_BUFFER.get();
        if (buffer.capacity() < size) {
            buffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
            CSDT_SCRATCH_BUFFER.set(buffer);
        }
        buffer.clear();
        buffer.limit(size);
        buffer.order(ByteOrder.nativeOrder());
        return buffer;
    }
}
