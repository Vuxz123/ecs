package com.ethnicthv.ecs.boid;

import com.ethnicthv.ecs.boid.debug.BenchmarkRunner;
import com.ethnicthv.ecs.boid.debug.WorldStatsCollector;
import com.ethnicthv.ecs.boid.render.BoidRenderer;
import com.ethnicthv.ecs.boid.render.CameraController;
import com.ethnicthv.ecs.boid.render.DebugOverlay;
import com.ethnicthv.ecs.boid.sim.BoidRuntime;
import com.ethnicthv.ecs.boid.sim.BoidSimulation;
import com.ethnicthv.ecs.boid.sim.SimulationConfig;
import com.ethnicthv.ecs.boid.sim.SteeringExecutionMode;
import com.ethnicthv.ecs.core.execution.SystemThreadMode;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MAJOR;
import static org.lwjgl.glfw.GLFW.GLFW_CONTEXT_VERSION_MINOR;
import static org.lwjgl.glfw.GLFW.GLFW_FALSE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_P;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_CORE_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_OPENGL_PROFILE;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;
import static org.lwjgl.glfw.GLFW.GLFW_RESIZABLE;
import static org.lwjgl.glfw.GLFW.GLFW_TRUE;
import static org.lwjgl.glfw.GLFW.GLFW_VISIBLE;
import static org.lwjgl.glfw.GLFW.glfwCreateWindow;
import static org.lwjgl.glfw.GLFW.glfwDefaultWindowHints;
import static org.lwjgl.glfw.GLFW.glfwDestroyWindow;
import static org.lwjgl.glfw.GLFW.glfwGetCursorPos;
import static org.lwjgl.glfw.GLFW.glfwGetPrimaryMonitor;
import static org.lwjgl.glfw.GLFW.glfwGetVideoMode;
import static org.lwjgl.glfw.GLFW.glfwGetWindowSize;
import static org.lwjgl.glfw.GLFW.glfwInit;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwSetCharCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorEnterCallback;
import static org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback;
import static org.lwjgl.glfw.GLFW.glfwSetErrorCallback;
import static org.lwjgl.glfw.GLFW.glfwSetFramebufferSizeCallback;
import static org.lwjgl.glfw.GLFW.glfwSetKeyCallback;
import static org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback;
import static org.lwjgl.glfw.GLFW.glfwSetScrollCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowFocusCallback;
import static org.lwjgl.glfw.GLFW.glfwSetWindowPos;
import static org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose;
import static org.lwjgl.glfw.GLFW.glfwShowWindow;
import static org.lwjgl.glfw.GLFW.glfwSwapBuffers;
import static org.lwjgl.glfw.GLFW.glfwSwapInterval;
import static org.lwjgl.glfw.GLFW.glfwTerminate;
import static org.lwjgl.glfw.GLFW.glfwWindowHint;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.opengl.GL11C.glViewport;
import static org.lwjgl.system.MemoryUtil.NULL;

public final class DemoMain {
    private static final int DENSITY_BASELINE_BOIDS = 25_000;

    private long window = NULL;
    private final SimulationConfig config = SimulationConfig.defaultConfig();
    private final InteractiveOptions interactiveOptions;
    private final BoidRuntime simulation;
    private final BoidRenderer renderer = new BoidRenderer();
    private final CameraController cameraController = new CameraController();
    private final DebugOverlay debugOverlay = new DebugOverlay();
    private final BenchmarkRunner benchmarkRunner = new BenchmarkRunner();
    private double fixedAccumulator;
    private double titleUpdateAccumulator;
    private double displayedFps;
    private long previousFrameTimeNanos;
    private int framesSinceTitleUpdate;
    private int framebufferWidth;
    private int framebufferHeight;

    private DemoMain(InteractiveOptions interactiveOptions) {
        this.interactiveOptions = interactiveOptions;
        this.simulation = new BoidRuntime(config, interactiveOptions.systemThreadMode());
    }

    public static void main(String[] args) {
        if (HeadlessBenchmarkOptions.isRequested(args)) {
            runHeadlessBenchmark(HeadlessBenchmarkOptions.parse(args));
            return;
        }
        new DemoMain(InteractiveOptions.parse(args)).run();
    }

    private static void runHeadlessBenchmark(HeadlessBenchmarkOptions options) {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(options.boidCount())
            .withRandomSeed(options.randomSeed())
            .withFastMath(options.fastMath())
            .withSteeringExecutionMode(options.executionMode());
        if (options.densityNormalized()) {
            config = densityNormalizedConfig(config, options.boidCount(), DENSITY_BASELINE_BOIDS);
        }

        BenchmarkRunner benchmarkRunner = new BenchmarkRunner();
        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            simulation.resetBoids(options.boidCount(), options.randomSeed());
            benchmarkRunner.start(options.warmupFrames(), options.sampleFrames());

            while (benchmarkRunner.isRunning()) {
                long startedAt = System.nanoTime();
                simulation.stepFixed();
                double wallFrameSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
                benchmarkRunner.update(
                    wallFrameSeconds,
                    simulation.getStats(),
                    0.0,
                    options.boidCount(),
                    0
                );
            }

            BenchmarkRunner.BenchmarkResult result = benchmarkRunner.lastResult();
            if (result == null) {
                throw new IllegalStateException("Headless benchmark did not produce a result");
            }

            Path exportDirectory = options.exportDirectory();
            String exportPath = benchmarkRunner.exportLastResult(exportDirectory);
            System.out.printf(
                Locale.ROOT,
                "Headless benchmark complete: boids=%d mode=%s fastMath=%s densityNormalized=%s worldHalfExtent=%.3f spawnExtent=%.3f avg_fps=%.2f avg_frame_ms=%.3f csv=%s%n",
                result.boidCount(),
                result.steeringMode(),
                options.fastMath(),
                options.densityNormalized(),
                config.worldHalfExtent(),
                config.spawnExtent(),
                result.averageFps(),
                result.averageFrameMillis(),
                exportPath
            );
        } catch (Exception exception) {
            throw new RuntimeException("Headless benchmark failed", exception);
        }
    }

    private static SimulationConfig densityNormalizedConfig(
        SimulationConfig baseConfig,
        int boidCount,
        int baselineBoidCount
    ) {
        if (boidCount <= 0) {
            throw new IllegalArgumentException("boidCount must be > 0");
        }
        if (baselineBoidCount <= 0) {
            throw new IllegalArgumentException("baselineBoidCount must be > 0");
        }

        double scale = Math.cbrt((double) boidCount / baselineBoidCount);
        float scaledSpawnExtent = (float) (baseConfig.spawnExtent() * scale);
        float scaledWorldHalfExtent = (float) (baseConfig.worldHalfExtent() * scale);
        return baseConfig.withWorldBounds(scaledSpawnExtent, scaledWorldHalfExtent);
    }

    private void run() {
        GLFWErrorCallback.createPrint(System.err).set();

        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        try {
            createWindow();
            simulation.bootstrap();
            renderer.bootstrap();
            debugOverlay.bootstrap(window);
            loop();
        } finally {
            debugOverlay.shutdown();
            renderer.shutdown();
            simulation.shutdown();
            cleanup();
        }
    }

    private void createWindow() {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        window = glfwCreateWindow(config.width(), config.height(), config.title(), NULL, NULL);
        if (window == NULL) {
            throw new IllegalStateException("Failed to create boid demo window");
        }

        framebufferWidth = config.width();
        framebufferHeight = config.height();
        glfwSetFramebufferSizeCallback(window, (handle, width, height) -> {
            framebufferWidth = Math.max(1, width);
            framebufferHeight = Math.max(1, height);
            glViewport(0, 0, framebufferWidth, framebufferHeight);
        });
        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) -> {
            debugOverlay.keyCallback(handle, key, scancode, action, mods);
            if (key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE) {
                glfwSetWindowShouldClose(handle, true);
                return;
            }
            if (key == GLFW_KEY_F1 && action == GLFW_RELEASE) {
                debugOverlay.toggleVisibility();
                return;
            }
            if (key == GLFW_KEY_P && action == GLFW_RELEASE) {
                simulation.toggleSteeringExecutionMode();
                updateWindowTitle(displayedFps);
            }
        });
        glfwSetMouseButtonCallback(window, (handle, button, action, mods) -> {
            debugOverlay.mouseButtonCallback(handle, button, action, mods);
            if (!debugOverlay.wantsMouse()) {
                cameraController.mouseButtonCallback(button, action);
            }
        });
        glfwSetScrollCallback(window, (handle, xOffset, yOffset) -> {
            debugOverlay.scrollCallback(handle, xOffset, yOffset);
            cameraController.scrollCallback(yOffset, debugOverlay.wantsMouse());
        });
        glfwSetCursorPosCallback(window, (handle, x, y) -> {
            debugOverlay.cursorPosCallback(handle, x, y);
            cameraController.cursorPosCallback(x, y, debugOverlay.wantsMouse());
        });
        glfwSetCursorEnterCallback(window, debugOverlay::cursorEnterCallback);
        glfwSetCharCallback(window, debugOverlay::charCallback);
        glfwSetWindowFocusCallback(window, debugOverlay::windowFocusCallback);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            glfwGetWindowSize(window, width, height);

            GLFWVidMode videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (videoMode != null) {
                glfwSetWindowPos(
                    window,
                    (videoMode.width() - width.get(0)) / 2,
                    (videoMode.height() - height.get(0)) / 2
                );
            }
        }

        glfwMakeContextCurrent(window);
        glfwSwapInterval(0);
        glfwShowWindow(window);
        GL.createCapabilities();
        primeCursorState();
    }

    private void loop() {
        previousFrameTimeNanos = System.nanoTime();

        while (!glfwWindowShouldClose(window)) {
            long now = System.nanoTime();
            double frameTimeSeconds = (now - previousFrameTimeNanos) / 1_000_000_000.0;
            previousFrameTimeNanos = now;
            fixedAccumulator += Math.min(frameTimeSeconds, 0.25);
            titleUpdateAccumulator += frameTimeSeconds;
            framesSinceTitleUpdate++;

            cameraController.update(frameTimeSeconds);
            debugOverlay.beginFrame();

            while (!debugOverlay.isPaused() && fixedAccumulator >= simulation.fixedDeltaTime()) {
                if (simulation.threadMode() == SystemThreadMode.MAIN_THREAD) {
                    simulation.stepFixed();
                }
                fixedAccumulator -= simulation.fixedDeltaTime();
            }

            float[] viewProjection = cameraController.buildViewProjectionMatrix(
                framebufferWidth,
                framebufferHeight,
                simulation.worldHalfExtent()
            );

            renderer.renderFrame(
                simulation,
                framebufferWidth,
                framebufferHeight,
                config.backgroundRed(),
                config.backgroundGreen(),
                config.backgroundBlue(),
                viewProjection
            );

            BoidSimulation.SimulationStats stats = simulation.stats();
            WorldStatsCollector.WorldStatsSnapshot worldStats = simulation.worldStats();
            debugOverlay.render(
                simulation,
                renderer,
                benchmarkRunner,
                cameraController,
                stats,
                worldStats,
                displayedFps,
                renderer.averageRenderMillis()
            );
            benchmarkRunner.update(
                frameTimeSeconds,
                stats,
                renderer.lastRenderMillis(),
                renderer.lastVisibleBoidCount(),
                renderer.renderStride()
            );

            glfwSwapBuffers(window);
            glfwPollEvents();

            if (titleUpdateAccumulator >= 0.5) {
                displayedFps = framesSinceTitleUpdate / titleUpdateAccumulator;
                updateWindowTitle(displayedFps);
                titleUpdateAccumulator = 0.0;
                framesSinceTitleUpdate = 0;
            }
        }
    }

    private void updateWindowTitle(double fps) {
        BoidSimulation.SimulationStats stats = simulation.stats();
        String fpsText = fps > 0.0 ? String.format("%.0f FPS", fps) : "warming";
        SteeringExecutionMode mode = stats.steeringExecutionMode();
        String title = String.format(
            "%s | %s | %d boids | %s | %s | ecs %.1f TPS | sim %.3f ms avg | steer %.3f ms avg | render %.3f ms avg",
            config.title(),
            fpsText,
            stats.boidCount(),
            simulation.threadMode(),
            mode,
            stats.currentWorldTicksPerSecond(),
            stats.averageFixedStepMillis(),
            stats.averageSteeringMillis(),
            renderer.averageRenderMillis()
        );
        org.lwjgl.glfw.GLFW.glfwSetWindowTitle(window, title);
    }

    private void primeCursorState() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x = stack.mallocDouble(1);
            var y = stack.mallocDouble(1);
            glfwGetCursorPos(window, x, y);
            cameraController.cursorPosCallback(x.get(0), y.get(0), false);
        }
    }

    private void cleanup() {
        if (window != NULL) {
            glfwDestroyWindow(window);
            window = NULL;
        }

        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) {
            callback.free();
        }
    }

    private record HeadlessBenchmarkOptions(
        int boidCount,
        int warmupFrames,
        int sampleFrames,
        long randomSeed,
        SteeringExecutionMode executionMode,
        boolean fastMath,
        boolean densityNormalized,
        Path exportDirectory
    ) {
        private static final String BENCHMARK_FLAG = "--headless-benchmark";

        static boolean isRequested(String[] args) {
            for (String arg : args) {
                if (BENCHMARK_FLAG.equals(arg)) {
                    return true;
                }
            }
            return false;
        }

        static HeadlessBenchmarkOptions parse(String[] args) {
            int boidCount = 100_000;
            int warmupFrames = 240;
            int sampleFrames = 600;
            long randomSeed = 0xB01DL;
            SteeringExecutionMode executionMode = SteeringExecutionMode.PARALLEL;
            boolean fastMath = true;
            boolean densityNormalized = true;
            Path exportDirectory = Path.of("benchmark-results");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (BENCHMARK_FLAG.equals(arg)) {
                    continue;
                }
                if ("--boids".equals(arg) && i + 1 < args.length) {
                    boidCount = Integer.parseInt(args[++i]);
                    continue;
                }
                if ("--warmup".equals(arg) && i + 1 < args.length) {
                    warmupFrames = Integer.parseInt(args[++i]);
                    continue;
                }
                if ("--samples".equals(arg) && i + 1 < args.length) {
                    sampleFrames = Integer.parseInt(args[++i]);
                    continue;
                }
                if ("--seed".equals(arg) && i + 1 < args.length) {
                    randomSeed = Long.parseLong(args[++i]);
                    continue;
                }
                if ("--mode".equals(arg) && i + 1 < args.length) {
                    executionMode = SteeringExecutionMode.valueOf(args[++i].toUpperCase(Locale.ROOT));
                    continue;
                }
                if ("--fast-math".equals(arg)) {
                    fastMath = true;
                    continue;
                }
                if ("--precise-math".equals(arg)) {
                    fastMath = false;
                    continue;
                }
                if ("--density-normalized".equals(arg)) {
                    densityNormalized = true;
                    continue;
                }
                if ("--fixed-bounds".equals(arg)) {
                    densityNormalized = false;
                    continue;
                }
                if ("--export-dir".equals(arg) && i + 1 < args.length) {
                    exportDirectory = Path.of(args[++i]);
                }
            }

            return new HeadlessBenchmarkOptions(
                boidCount,
                warmupFrames,
                sampleFrames,
                randomSeed,
                executionMode,
                fastMath,
                densityNormalized,
                exportDirectory
            );
        }
    }

    private record InteractiveOptions(SystemThreadMode systemThreadMode) {
        static InteractiveOptions parse(String[] args) {
            SystemThreadMode systemThreadMode = SystemThreadMode.DEDICATED_THREAD;
            for (int i = 0; i < args.length; i++) {
                if ("--system-thread".equals(args[i]) && i + 1 < args.length) {
                    systemThreadMode = switch (args[++i].toLowerCase(Locale.ROOT)) {
                        case "main", "main_thread", "main-thread" -> SystemThreadMode.MAIN_THREAD;
                        case "dedicated", "dedicated_thread", "dedicated-thread" -> SystemThreadMode.DEDICATED_THREAD;
                        default -> throw new IllegalArgumentException("Unknown system thread mode: " + args[i]);
                    };
                }
            }
            return new InteractiveOptions(systemThreadMode);
        }
    }
}
