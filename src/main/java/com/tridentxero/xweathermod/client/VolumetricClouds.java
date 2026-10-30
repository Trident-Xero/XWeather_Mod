package com.tridentxero.xweathermod.client;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tridentxero.xweathermod.XWeatherMod;
import com.tridentxero.xweathermod.storm.ClientStormState;
import com.tridentxero.xweathermod.storm.StormCell;
import com.tridentxero.xweathermod.storm.StormSnapshot;
import com.tridentxero.xweathermod.storm.TornadoSnapshot;

import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.RegisterRenderPipelinesEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * Client only. The VOLUMETRIC cloud mode: instead of thousands of billboard puffs, one full-screen ray-marching
 * shader (assets/xweathermod/shaders/core/volumetric.vsh / .fsh) draws the storm layer, the supercell parts, the wall
 * cloud, the tail cloud, the tornado funnel and its dust skirt as one volume.
 *
 * HOW IT HOOKS IN: a RenderLevelStageEvent.AfterTranslucentParticles listener opens its own render pass on the main
 * target and draws a full-screen triangle with this shader. The camera comes from ExtractLevelRenderStateEvent.
 * Terrain hides the cloud through the hardware depth test against the depth the shader writes (first-hit depth), so
 * a cloud that starts in front of terrain but extends behind it is not clipped exactly. Unverified in game: whether a
 * new render pass may be opened at this stage.
 *
 * SAFETY: any exception in the draw switches this mode off for the rest of the session ({@link #active} returns false)
 * and the particle clouds take over. The same happens if the hook is not called for 3 seconds (for example the event never fires), so you can never end up with no clouds at all.
 */
@EventBusSubscriber(modid = XWeatherMod.MOD_ID, value = Dist.CLIENT)
public final class VolumetricClouds {

    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(XWeatherMod.MOD_ID, "core/volumetric");
    private static final int MAX_STORMS = 6;
    private static final int MAX_TORNADOES = 6;
    /** mat4 + 4 vec4 + 3 * 6 vec4 (storms) + 3 * 6 vec4 (tornadoes). Matches the XWeatherData block in the shader. */
    private static final int UBO_SIZE = 64 + 4 * 16 + 3 * MAX_STORMS * 16 + 3 * MAX_TORNADOES * 16;
    private static final double WRAP = 16384.0;
    private static final long HOOK_TIMEOUT_NANOS = 3_000_000_000L;
    private static final long START_NANOS = System.nanoTime();

    private static RenderPipeline pipelineMainTarget;
    private static volatile Camera camera;

    private static GpuBuffer ubo;
    private static volatile boolean broken = false;
    private static volatile long lastHookNanos = System.nanoTime();
    private static volatile long lastDrawNanos = 0L;
    private static boolean hidden = false;
    private static CloudStatus originalClouds = CloudStatus.FANCY;
    private static long hiddenSinceNanos = 0L;
    private static boolean autoHideDisabled = false;
    private static boolean loggedFirstDraw = false;
    private static boolean loggedFallback = false;

    private VolumetricClouds() {
    }

    /**
     * True when the volumetric renderer is the one drawing storm clouds right now. The particle spawners check this
     * and stand down; they come back if it turns false.
     */
    public static boolean active() {
        if (ClientConfig.CLOUD_MODE.get() != ClientConfig.CloudMode.VOLUMETRIC || broken) {
            return false;
        }
        boolean hooked = System.nanoTime() - lastHookNanos < HOOK_TIMEOUT_NANOS;
        if (!hooked && !loggedFallback) {
            loggedFallback = true;
            XWeatherMod.LOGGER.warn("[volumetric] the render-stage event has not fired for 3 seconds. "
                    + "Using particle clouds until it does.");
        }
        if (hooked) {
            loggedFallback = false;
        }
        return hooked;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Registration (called from XWeatherModClient on the mod event bus)
    // ---------------------------------------------------------------------------------------------------------

    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        // Drawn into the main target after translucent particles; the hardware depth test hides it behind terrain.
        pipelineMainTarget = RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath(XWeatherMod.MOD_ID, "pipeline/volumetric_clouds_main"))
                .withVertexShader(SHADER)
                .withFragmentShader(SHADER)
                .withUniform("Projection", UniformType.UNIFORM_BUFFER)
                .withUniform("XWeatherData", UniformType.UNIFORM_BUFFER)
                .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false))
                .withCull(false)
                .withVertexFormat(DefaultVertexFormat.EMPTY, VertexFormat.Mode.TRIANGLES)
                .build();
        event.registerPipeline(pipelineMainTarget);
    }

    /**
     * Hides the vanilla cloud layer (Video Settings -> Clouds) while a volumetric storm is being drawn, and puts the
     * player's setting back afterwards. Unverified in game: Minecraft#options, Options#cloudStatus() returning an
     * OptionInstance with get()/set(). If the draw stops right after we hide the clouds (so the volumetric pass
     * secretly depends on them), auto-hide switches itself off for the session instead of flickering.
     */
    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean drawing = System.nanoTime() - lastDrawNanos < 1_000_000_000L;
        boolean want = ClientConfig.VOLUMETRIC_HIDE_VANILLA.get() && !autoHideDisabled && mc.level != null && active()
                && drawing;
        var option = mc.options.cloudStatus();
        if (hidden && option.get() != CloudStatus.OFF) {
            hidden = false;            // the player changed it themselves: leave their choice alone
            return;
        }
        if (want && !hidden) {
            CloudStatus now = option.get();
            if (now != CloudStatus.OFF) {
                originalClouds = now;
                option.set(CloudStatus.OFF);
                hidden = true;
                hiddenSinceNanos = System.nanoTime();
            }
        } else if (!want && hidden) {
            if (System.nanoTime() - hiddenSinceNanos < 3_000_000_000L && mc.level != null && !drawing) {
                autoHideDisabled = true;
                XWeatherMod.LOGGER.warn("[volumetric] drawing stopped as soon as vanilla clouds were hidden; "
                        + "automatic hiding is off for this session");
            }
            option.set(originalClouds);
            hidden = false;
        }
    }

    /** Remember the camera (game event bus, once per frame). */
    @SubscribeEvent
    public static void onExtract(ExtractLevelRenderStateEvent event) {
        camera = event.getCamera();
    }

    /**
     * Draw after translucent particles, inside the level render. v26 tried the NeoForge custom-clouds hook instead;
     * the log proved it was never called, so this uses a render-stage event.
     */
    @SubscribeEvent
    public static void onStage(RenderLevelStageEvent.AfterTranslucentParticles event) {
        lastHookNanos = System.nanoTime();
        if (ClientConfig.CLOUD_MODE.get() != ClientConfig.CloudMode.VOLUMETRIC || broken) {
            return;
        }
        Camera cam = camera;
        if (cam == null) {
            return;
        }
        try {
            draw(cam);
        } catch (Throwable t) {
            broken = true;
            XWeatherMod.LOGGER.error("[volumetric] drawing failed; switching back to particle clouds for this session", t);
        }
    }

    private static void draw(Camera camera) {
        Vec3 cam = camera.position();
        lastDrawNanos = System.nanoTime();
        List<StormSnapshot> allStorms = ClientStormState.storms();
        if (allStorms.isEmpty()) {
            return;
        }
        double elapsedTicks = (System.nanoTime() - ClientStormState.lastUpdateNanos()) / 50_000_000.0;
        if (elapsedTicks > 100.0) {
            return; // stale data
        }
        Minecraft mc = Minecraft.getInstance();
        double maxDist = ClientConfig.VOLUMETRIC_DISTANCE.get();

        // Nearest storms first (distance to the storm's edge), capped at what the shader holds.
        List<StormSnapshot> storms = new ArrayList<>();
        for (StormSnapshot s : allStorms) {
            double dx = s.x() + s.vx() * elapsedTicks - cam.x;
            double dz = s.z() + s.vz() * elapsedTicks - cam.z;
            double edge = Math.sqrt(dx * dx + dz * dz) - s.radius() * (s.supercell() ? 2.0 : 1.0);
            if (edge < maxDist && s.intensity() > 0.02F) {
                storms.add(s);
            }
        }
        if (storms.isEmpty()) {
            return;
        }
        storms.sort(Comparator.comparingDouble(s -> {
            double dx = s.x() + s.vx() * elapsedTicks - cam.x;
            double dz = s.z() + s.vz() * elapsedTicks - cam.z;
            return Math.sqrt(dx * dx + dz * dz) - s.radius();
        }));
        if (storms.size() > MAX_STORMS) {
            storms = new ArrayList<>(storms.subList(0, MAX_STORMS));
        }

        List<TornadoSnapshot> tornadoes = new ArrayList<>();
        if (ClientConfig.FUNNEL_ENABLED.get()) {
            for (TornadoSnapshot t : ClientStormState.tornadoes()) {
                if (FunnelSpawner.view(t.id()) != null) {
                    tornadoes.add(t);
                }
            }
            tornadoes.sort(Comparator.comparingDouble(t -> {
                double dx = t.x() + t.vx() * elapsedTicks - cam.x;
                double dz = t.z() + t.vz() * elapsedTicks - cam.z;
                return dx * dx + dz * dz;
            }));
            if (tornadoes.size() > MAX_TORNADOES) {
                tornadoes = new ArrayList<>(tornadoes.subList(0, MAX_TORNADOES));
            }
        }

        RenderTarget target = mc.getMainRenderTarget();

        GpuDevice device = RenderSystem.getDevice();
        if (ubo == null) {
            ubo = device.createBuffer(() -> "XWeather volumetric data", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
                    (long) UBO_SIZE);
        }

        // Cloud light colour: time of day approximated from the game time (assumes the normal 24000-tick day).
        double dayT = (mc.level != null ? mc.level.getGameTime() % 24000L : 6000L) / 24000.0;
        float lum = (float) Math.max(0.12, Math.min(1.0, 0.5 + 0.8 * Math.sin(2.0 * Math.PI * dayT)));
        int color = 0xFF000000 | (Math.round(lum * 255) << 16) | (Math.round(lum * 255) << 8) | Math.round(lum * 255);

        CommandEncoder encoder = device.createCommandEncoder();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer data = writeData(Std140Builder.onStack(stack, UBO_SIZE), cam, color, camera.getViewRotationMatrix(new Matrix4f()), device,
                    storms, tornadoes, elapsedTicks);
            encoder.writeToBuffer(ubo.slice(), data);
        }

        GpuTextureView colorView = target.getColorTextureView();
        GpuTextureView depthView = target.getDepthTextureView();
        RenderPass pass = depthView != null
                ? encoder.createRenderPass(() -> "XWeather volumetric clouds", colorView, OptionalInt.empty(), depthView,
                        OptionalDouble.empty())
                : encoder.createRenderPass(() -> "XWeather volumetric clouds", colorView, OptionalInt.empty());
        try (pass) {
            pass.setPipeline(pipelineMainTarget);
            pass.setUniform("Projection", RenderSystem.getProjectionMatrixBuffer());
            pass.setUniform("XWeatherData", ubo);
            pass.draw(0, 3);
        }

        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            XWeatherMod.LOGGER.info("[volumetric] first draw: storms={}, tornadoes={}, main target {}x{}",
                    storms.size(), tornadoes.size(), target.width, target.height);
        }
    }

    /** Packs the XWeatherData block. The order and sizes must match volumetric.fsh exactly. */
    private static ByteBuffer writeData(Std140Builder b, Vec3 cam, int color, Matrix4f invViewSource, GpuDevice device,
                                        List<StormSnapshot> storms, List<TornadoSnapshot> tornadoes, double elapsedTicks) {
        // View -> world rotation. The shader only uses its upper 3x3.
        Matrix4f invView = new Matrix4f(invViewSource).invert();
        b.putMat4f(invView);

        double wrapX = cam.x - Math.floor(cam.x / WRAP) * WRAP;
        double wrapZ = cam.z - Math.floor(cam.z / WRAP) * WRAP;
        float seconds = (float) (((System.nanoTime() - START_NANOS) / 1.0e9) % 100000.0);
        b.putVec4((float) wrapX, (float) cam.y, (float) wrapZ, seconds);                                  // CamPos
        b.putVec4(ClientConfig.VOLUMETRIC_STEPS.get(), ClientConfig.VOLUMETRIC_DISTANCE.get(),
                device.isZZeroToOne() ? 1.0F : 0.0F, ClientConfig.VOLUMETRIC_DENSITY.get().floatValue());   // Cfg
        b.putVec4(ClientConfig.ALTITUDE.get(), storms.size(), tornadoes.size(), 0.0F);                    // Params

        float r = ((color >> 16) & 255) / 255.0F;
        float g = ((color >> 8) & 255) / 255.0F;
        float bl = (color & 255) / 255.0F;
        if (r + g + bl < 0.03F) {
            r = 0.9F;      // an unexpected value: fall back to a neutral daylight cloud colour
            g = 0.9F;
            bl = 0.9F;
        }
        b.putVec4(r, g, bl, 0.0F);                                                                        // LightCol

        // SA: x, z (camera relative), radius, development
        for (int i = 0; i < MAX_STORMS; i++) {
            if (i < storms.size()) {
                StormSnapshot s = storms.get(i);
                b.putVec4((float) (s.x() + s.vx() * elapsedTicks - cam.x), (float) (s.z() + s.vz() * elapsedTicks - cam.z),
                        s.radius(), s.development());
            } else {
                b.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
        // SB: intensity, supercell flag, forward x, forward z
        for (int i = 0; i < MAX_STORMS; i++) {
            if (i < storms.size()) {
                StormSnapshot s = storms.get(i);
                double sp = Math.sqrt((double) s.vx() * s.vx() + (double) s.vz() * s.vz());
                float fx = sp > 1.0E-9 ? (float) (s.vx() / sp) : 1.0F;
                float fz = sp > 1.0E-9 ? (float) (s.vz() / sp) : 0.0F;
                b.putVec4(s.intensity(), s.supercell() ? 1.0F : 0.0F, fx, fz);
            } else {
                b.putVec4(0.0F, 0.0F, 1.0F, 0.0F);
            }
        }
        // SC: wall cloud x, z (camera relative), radius (0 = none), lowest Y
        for (int i = 0; i < MAX_STORMS; i++) {
            FunnelSpawner.WallView w = i < storms.size() ? FunnelSpawner.wallView(storms.get(i).id()) : null;
            if (w != null) {
                StormSnapshot s = storms.get(i);
                double sp = Math.sqrt((double) s.vx() * s.vx() + (double) s.vz() * s.vz());
                double fx = sp > 1.0E-9 ? s.vx() / sp : 1.0;
                double fz = sp > 1.0E-9 ? s.vz() / sp : 0.0;
                double cx = s.x() + s.vx() * elapsedTicks;
                double cz = s.z() + s.vz() * elapsedTicks;
                double wx = cx - fx * s.radius() * StormCell.TORNADO_BACK_FRACTION;
                double wz = cz - fz * s.radius() * StormCell.TORNADO_BACK_FRACTION;
                b.putVec4((float) (wx - cam.x), (float) (wz - cam.z), (float) w.R(), (float) w.topY());
            } else {
                b.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
        // TA: x, z (camera relative), size, ground Y
        for (int i = 0; i < MAX_TORNADOES; i++) {
            FunnelSpawner.TornadoView v = i < tornadoes.size() ? FunnelSpawner.view(tornadoes.get(i).id()) : null;
            if (v != null) {
                TornadoSnapshot t = tornadoes.get(i);
                b.putVec4((float) (t.x() + t.vx() * elapsedTicks - cam.x), (float) (t.z() + t.vz() * elapsedTicks - cam.z),
                        (float) Math.min(v.size(), 20000.0), (float) v.baseY());
            } else {
                b.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
        // TB: top Y, intensity, wind mph, unused
        for (int i = 0; i < MAX_TORNADOES; i++) {
            FunnelSpawner.TornadoView v = i < tornadoes.size() ? FunnelSpawner.view(tornadoes.get(i).id()) : null;
            if (v != null) {
                b.putVec4((float) v.topY(), (float) v.intensity(), (float) v.windMph(), 0.0F);
            } else {
                b.putVec4(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
        // TC: travel direction x, z
        for (int i = 0; i < MAX_TORNADOES; i++) {
            if (i < tornadoes.size()) {
                TornadoSnapshot t = tornadoes.get(i);
                double sp = Math.sqrt((double) t.vx() * t.vx() + (double) t.vz() * t.vz());
                float fx = sp > 1.0E-9 ? (float) (t.vx() / sp) : 1.0F;
                float fz = sp > 1.0E-9 ? (float) (t.vz() / sp) : 0.0F;
                b.putVec4(fx, fz, 0.0F, 0.0F);
            } else {
                b.putVec4(1.0F, 0.0F, 0.0F, 0.0F);
            }
        }
        return b.get();
    }
}
