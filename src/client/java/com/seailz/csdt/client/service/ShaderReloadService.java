package com.seailz.csdt.client.service;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.logging.LogUtils;
import com.seailz.csdt.client.mixins.ShaderManagerMixin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.slf4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Util to help with reloading shaders without triggering a full resource pack reload.
 */
public final class ShaderReloadService {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ReloadScope, ReloadStat> STATS = new EnumMap<>(ReloadScope.class);
    private static ReloadScope pendingReloadScope;

    static {
        for (ReloadScope scope : ReloadScope.values()) {
            STATS.put(scope, ReloadStat.empty());
        }
    }

    private ShaderReloadService() {
    }

    public static void reloadCoreShadersOnly() {
        enqueueReload(ReloadScope.CORE_ONLY);
    }

    public static void reloadPostShadersOnly() {
        enqueueReload(ReloadScope.POST_ONLY);
    }

    public static void reloadAllShaders() {
        enqueueReload(ReloadScope.ALL);
    }

    public static void reloadAllShadersFromHub() {
        ShaderResourceOverrideService.clearVisualizations();
        ForcedPostEffectService.clearForcedPostEffect();
        enqueueReload(ReloadScope.ALL);
    }

    public static ReloadStat getStat(ReloadScope scope) {
        return STATS.get(scope);
    }

    private static void enqueueReload(ReloadScope scope) {
        synchronized (ShaderReloadService.class) {
            pendingReloadScope = mergePendingScope(pendingReloadScope, scope);
        }
    }

    public static void runPendingReloadAfterFrame(Minecraft minecraft) {
        ReloadScope scope;
        synchronized (ShaderReloadService.class) {
            scope = pendingReloadScope;
            pendingReloadScope = null;
        }
        if (scope != null) {
            reloadNow(minecraft, scope);
        }
    }

    private static ReloadScope mergePendingScope(ReloadScope current, ReloadScope next) {
        if (current == null || current == next) {
            return next;
        }
        return ReloadScope.ALL;
    }

    private static void reloadNow(Minecraft minecraft, ReloadScope scope) {
        long startedAt = System.nanoTime();
        try {
            ShaderManager shaderManager = minecraft.getShaderManager();
            ResourceManager resourceManager = minecraft.getResourceManager();
            ProfilerFiller profiler = Profiler.get();
            ShaderManager.Configs prepared = ((ShaderManagerMixin) shaderManager).csdt$prepare(resourceManager, profiler);
            prepared = ShaderResourceOverrideService.applyOverrides(prepared, resourceManager);
            ShaderManager.Configs current = currentConfigs(shaderManager);
            ShaderManager.Configs merged = switch (scope) {
                case CORE_ONLY -> new ShaderManager.Configs(prepared.shaderSources(), current.postChains());
                case POST_ONLY -> new ShaderManager.Configs(current.shaderSources(), prepared.postChains());
                case ALL -> prepared;
            };

            if (scope == ReloadScope.CORE_ONLY) {
                replaceCompilationCachePreservingPostChains(shaderManager, merged);
            } else if (scope == ReloadScope.POST_ONLY) {
                replaceCompilationCacheClosingPostChains(shaderManager, merged);
            } else {
                ((ShaderManagerMixin) shaderManager).csdt$apply(merged, resourceManager, profiler);
            }

            ReloadStat stat = ReloadStat.success(System.currentTimeMillis(), nanosToMillis(startedAt, System.nanoTime()));
            STATS.put(scope, stat);
            ClientToastService.showReloadResult(scope, stat);
        } catch (Exception exception) {
            ReloadStat stat = ReloadStat.failure(System.currentTimeMillis(), nanosToMillis(startedAt, System.nanoTime()), exception.getClass().getSimpleName() + ": " + exception.getMessage());
            STATS.put(scope, stat);
            ClientToastService.showReloadResult(scope, stat);
            LOGGER.error("Failed to reload {} shaders", scope.logName, exception);
        }
    }

    private static long nanosToMillis(long startedAt, long endedAt) {
        return Duration.ofNanos(endedAt - startedAt).toMillis();
    }

    private static ShaderManager.Configs currentConfigs(ShaderManager shaderManager) throws ReflectiveOperationException {
        Object compilationCache = compilationCacheField().get(shaderManager);
        return (ShaderManager.Configs) compilationConfigsField().get(compilationCache);
    }

    private static void replaceCompilationCachePreservingPostChains(ShaderManager shaderManager, ShaderManager.Configs configs) throws ReflectiveOperationException {
        Field compilationCacheField = compilationCacheField();
        Object oldCache = compilationCacheField.get(shaderManager);
        Object newCache = newCompilationCache(shaderManager, configs);
        precompileStaticPipelines(newCache);

        Map<?, ?> oldPostChains = postChains(oldCache);
        Map<Object, Object> newPostChains = postChains(newCache);
        // Core-only reloads keep post effect configs and must also keep any live PostChain instances alive.
        newPostChains.putAll(oldPostChains);
        oldPostChains.clear();

        closeCompilationCache(oldCache);
        compilationCacheField.set(shaderManager, newCache);
    }

    private static void replaceCompilationCacheClosingPostChains(ShaderManager shaderManager, ShaderManager.Configs configs) throws ReflectiveOperationException {
        Field compilationCacheField = compilationCacheField();
        Object oldCache = compilationCacheField.get(shaderManager);
        closeCompilationCache(oldCache);
        compilationCacheField.set(shaderManager, newCompilationCache(shaderManager, configs));
    }

    private static void closeCompilationCache(Object cache) throws ReflectiveOperationException {
        Method closeMethod = cache.getClass().getDeclaredMethod("close");
        closeMethod.setAccessible(true);
        closeMethod.invoke(cache);
    }

    private static void precompileStaticPipelines(Object compilationCache) {
        HashSet<RenderPipeline> pipelines = new HashSet<>(RenderPipelines.getStaticPipelines());
        List<Identifier> invalidPipelines = new ArrayList<>();
        GpuDevice device = RenderSystem.getDevice();
        device.clearPipelineCache();
        ShaderSource shaderSource = (identifier, shaderType) -> {
            try {
                return (String) getShaderSourceMethod().invoke(compilationCache, identifier, shaderType);
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("Failed to read shader source for " + identifier + " " + shaderType, exception);
            }
        };

        for (RenderPipeline pipeline : pipelines) {
            CompiledRenderPipeline compiled = device.precompilePipeline(pipeline, shaderSource);
            if (!compiled.isValid()) {
                invalidPipelines.add(pipeline.getLocation());
            }
        }

        if (!invalidPipelines.isEmpty()) {
            device.clearPipelineCache();
            device.loadCriticalShaders();
            throw new RuntimeException("Failed to compile core shader pipeline(s):\n" + String.join("\n", invalidPipelines.stream().map(Identifier::toString).toList()));
        }
    }

    private static Object newCompilationCache(ShaderManager shaderManager, ShaderManager.Configs configs) throws ReflectiveOperationException {
        Constructor<?> constructor = Class.forName("net.minecraft.client.renderer.ShaderManager$CompilationCache").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            if (ShaderManager.class.isAssignableFrom(parameterType)) {
                arguments[i] = shaderManager;
            } else if (ShaderManager.Configs.class.isAssignableFrom(parameterType)) {
                arguments[i] = configs;
            } else {
                throw new IllegalStateException("Unsupported ShaderManager$CompilationCache constructor parameter: " + parameterType.getName());
            }
        }
        return constructor.newInstance(arguments);
    }

    private static Field compilationCacheField() throws NoSuchFieldException {
        Field field = ShaderManager.class.getDeclaredField("compilationCache");
        field.setAccessible(true);
        return field;
    }

    private static Field compilationConfigsField() throws ClassNotFoundException, NoSuchFieldException {
        Field field = Class.forName("net.minecraft.client.renderer.ShaderManager$CompilationCache").getDeclaredField("configs");
        field.setAccessible(true);
        return field;
    }

    private static Field postChainsField() throws ClassNotFoundException, NoSuchFieldException {
        Field field = Class.forName("net.minecraft.client.renderer.ShaderManager$CompilationCache").getDeclaredField("postChains");
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> postChains(Object compilationCache) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        return (Map<Object, Object>) postChainsField().get(compilationCache);
    }

    private static Method getShaderSourceMethod() throws ClassNotFoundException, NoSuchMethodException {
        Method method = Class.forName("net.minecraft.client.renderer.ShaderManager$CompilationCache")
                .getDeclaredMethod("getShaderSource", Identifier.class, com.mojang.blaze3d.shaders.ShaderType.class);
        method.setAccessible(true);
        return method;
    }

    public enum ReloadScope {
        CORE_ONLY("core", "Core"),
        POST_ONLY("post", "Post"),
        ALL("all", "All");

        private final String logName;
        private final String label;

        ReloadScope(String logName, String label) {
            this.logName = logName;
            this.label = label;
        }

        public String label() {
            return this.label;
        }
    }

    public record ReloadStat(boolean success, long finishedAtMillis, long durationMillis, String message) {

        private static ReloadStat empty() {
            return new ReloadStat(true, 0L, -1L, "No reload yet");
        }

        private static ReloadStat success(long finishedAtMillis, long durationMillis) {
            return new ReloadStat(true, finishedAtMillis, durationMillis, "OK");
        }

        private static ReloadStat failure(long finishedAtMillis, long durationMillis, String message) {
            return new ReloadStat(false, finishedAtMillis, durationMillis, message == null ? "Unknown error" : message);
        }
    }
}
