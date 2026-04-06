package com.ethnicthv.ecs.boid.render;

import com.ethnicthv.ecs.boid.debug.BenchmarkRunner;
import com.ethnicthv.ecs.boid.debug.WorldStatsCollector;
import com.ethnicthv.ecs.boid.sim.BoidRuntime;
import com.ethnicthv.ecs.boid.sim.BoidSimulation;
import com.ethnicthv.ecs.boid.sim.SteeringExecutionMode;
import com.ethnicthv.ecs.core.execution.SystemThreadMode;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.internal.ImGuiContext;
import imgui.type.ImBoolean;

public final class DebugOverlay {
    private static final int READABILITY_COUNT = 25_000;
    private static final int PERFORMANCE_COUNT = 100_000;
    private static final int STRESS_COUNT = 250_000;
    private static final int EXTREME_STRESS_COUNT = 1_000_000;

    private final ImGuiImplGlfw imGuiGlfw = new ImGuiImplGlfw();
    private final ImGuiImplGl3 imGuiGl3 = new ImGuiImplGl3();
    private final ImBoolean overlayVisible = new ImBoolean(true);
    private final ImBoolean paused = new ImBoolean(false);
    private final ImBoolean autoOrbit = new ImBoolean(true);

    private final float[] separationWeight = new float[1];
    private final float[] alignmentWeight = new float[1];
    private final float[] cohesionWeight = new float[1];
    private final float[] maxForce = new float[1];
    private final float[] cameraDistance = new float[1];
    private final int[] renderStride = new int[] { 1 };
    private ImGuiContext context;
    private boolean glfwInitialized;
    private boolean gl3Initialized;

    public void bootstrap(long window) {
        context = ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        glfwInitialized = imGuiGlfw.init(window, false);
        gl3Initialized = imGuiGl3.init("#version 330 core");
    }

    public void shutdown() {
        if (context == null) {
            return;
        }

        ImGui.setCurrentContext(context);
        if (gl3Initialized) {
            imGuiGl3.shutdown();
            gl3Initialized = false;
        }
        if (glfwInitialized) {
            imGuiGlfw.shutdown();
            glfwInitialized = false;
        }
        ImGui.destroyContext(context);
        context = null;
    }

    public void beginFrame() {
        if (context == null || !glfwInitialized || !gl3Initialized) {
            return;
        }

        ImGui.setCurrentContext(context);
        imGuiGlfw.newFrame();
        imGuiGl3.newFrame();
        ImGui.newFrame();
    }

    public void render(
        BoidRuntime simulation,
        BoidRenderer renderer,
        BenchmarkRunner benchmarkRunner,
        CameraController cameraController,
        BoidSimulation.SimulationStats simulationStats,
        WorldStatsCollector.WorldStatsSnapshot worldStats,
        double fps,
        double renderMillis
    ) {
        if (context == null || !glfwInitialized || !gl3Initialized) {
            return;
        }

        ImGui.setCurrentContext(context);
        autoOrbit.set(cameraController.autoOrbit());
        paused.set(simulation.isPaused());
        cameraDistance[0] = cameraController.distance();
        separationWeight[0] = simulation.separationWeight();
        alignmentWeight[0] = simulation.alignmentWeight();
        cohesionWeight[0] = simulation.cohesionWeight();
        maxForce[0] = simulation.maxForce();
        renderStride[0] = renderer.renderStride();

        if (overlayVisible.get()) {
            drawOverlay(simulation, renderer, benchmarkRunner, cameraController, simulationStats, worldStats, fps, renderMillis);
        }

        ImGui.render();
        imGuiGl3.renderDrawData(ImGui.getDrawData());
    }

    public boolean isPaused() {
        return paused.get();
    }

    public boolean wantsMouse() {
        if (context == null) {
            return false;
        }
        ImGui.setCurrentContext(context);
        return ImGui.getIO().getWantCaptureMouse();
    }

    public boolean wantsKeyboard() {
        if (context == null) {
            return false;
        }
        ImGui.setCurrentContext(context);
        return ImGui.getIO().getWantCaptureKeyboard();
    }

    public void toggleVisibility() {
        overlayVisible.set(!overlayVisible.get());
    }

    public void mouseButtonCallback(long window, int button, int action, int mods) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.mouseButtonCallback(window, button, action, mods);
    }

    public void scrollCallback(long window, double xOffset, double yOffset) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.scrollCallback(window, xOffset, yOffset);
    }

    public void keyCallback(long window, int key, int scancode, int action, int mods) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.keyCallback(window, key, scancode, action, mods);
    }

    public void charCallback(long window, int codepoint) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.charCallback(window, codepoint);
    }

    public void cursorPosCallback(long window, double x, double y) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.cursorPosCallback(window, x, y);
    }

    public void cursorEnterCallback(long window, boolean entered) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.cursorEnterCallback(window, entered);
    }

    public void windowFocusCallback(long window, boolean focused) {
        if (!glfwInitialized) {
            return;
        }
        imGuiGlfw.windowFocusCallback(window, focused);
    }

    private void drawOverlay(
        BoidRuntime simulation,
        BoidRenderer renderer,
        BenchmarkRunner benchmarkRunner,
        CameraController cameraController,
        BoidSimulation.SimulationStats simulationStats,
        WorldStatsCollector.WorldStatsSnapshot worldStats,
        double fps,
        double renderMillis
    ) {
        ImGui.setNextWindowPos(18.0f, 18.0f, ImGuiCond.FirstUseEver);
        ImGui.setNextWindowSize(430.0f, 560.0f, ImGuiCond.FirstUseEver);
        if (!ImGui.begin("Boid Debug Overlay", ImGuiWindowFlags.None)) {
            ImGui.end();
            return;
        }

        ImGui.text(String.format("FPS: %.1f", fps));
        ImGui.text(String.format("Boids: %,d", simulationStats.boidCount()));
        ImGui.text(String.format("Visible: %,d", renderer.lastVisibleBoidCount()));
        ImGui.text(String.format("Mode: %s", simulationStats.steeringExecutionMode()));
        ImGui.text(String.format("Runtime: %s", simulation.threadMode()));
        ImGui.text(String.format("Paused: %s", simulation.isPaused() ? "yes" : "no"));
        ImGui.separator();

        ImGui.text(String.format("Sim avg: %.3f ms", simulationStats.averageFixedStepMillis()));
        ImGui.text(String.format("Steer avg: %.3f ms", simulationStats.averageSteeringMillis()));
        ImGui.text(String.format("Render avg: %.3f ms", renderMillis));
        ImGui.text(String.format("Fixed tick: %.1f Hz", 1.0f / simulationStats.fixedDeltaTime()));
        ImGui.text(String.format("ECS TPS: %.1f current | %.1f avg", simulationStats.currentWorldTicksPerSecond(), simulationStats.averageWorldTicksPerSecond()));
        ImGui.separator();

        ImGui.text(String.format("Archetypes: %d", worldStats.archetypeCount()));
        ImGui.text(String.format("Chunk groups: %d", worldStats.chunkGroupCount()));
        ImGui.text(String.format("Chunks: %d", worldStats.chunkCount()));
        ImGui.text(String.format("Chunk slots: %,d / %,d", worldStats.totalChunkEntities(), worldStats.totalChunkCapacity()));
        ImGui.text(String.format("Chunk occupancy: %.1f%%", worldStats.averageChunkOccupancy() * 100.0f));
        ImGui.plotHistogram(
            "Occupancy",
            worldStats.occupancyHistogram(),
            worldStats.occupancyHistogram().length,
            0,
            "Chunk fill buckets",
            0.0f,
            max(worldStats.occupancyHistogram()),
            380.0f,
            72.0f
        );
        for (WorldStatsCollector.OccupancyBucket bucket : worldStats.topOccupancyBuckets()) {
            ImGui.text(String.format(
                "Top bucket %.0f-%.0f%%: %,d chunks",
                bucket.minFillPercent(),
                bucket.maxFillPercent(),
                bucket.chunkCount()
            ));
        }

        if (ImGui.collapsingHeader("Mode And Presets")) {
            if (ImGui.radioButton("Sequential", simulationStats.steeringExecutionMode() == SteeringExecutionMode.SEQUENTIAL)) {
                simulation.setSteeringExecutionMode(SteeringExecutionMode.SEQUENTIAL);
            }
            ImGui.sameLine();
            if (ImGui.radioButton("Parallel", simulationStats.steeringExecutionMode() == SteeringExecutionMode.PARALLEL)) {
                simulation.setSteeringExecutionMode(SteeringExecutionMode.PARALLEL);
            }

            if (ImGui.button("Readability 25k")) {
                simulation.resetBoids(READABILITY_COUNT, System.nanoTime());
            }
            ImGui.sameLine();
            if (ImGui.button("Perf 100k")) {
                simulation.resetBoids(PERFORMANCE_COUNT, System.nanoTime());
            }
            ImGui.sameLine();
            if (ImGui.button("Stress 250k")) {
                simulation.resetBoids(STRESS_COUNT, System.nanoTime());
            }

            if (ImGui.button("Reset Current")) {
                simulation.resetBoids(simulation.boidCount(), System.nanoTime());
            }
            ImGui.sameLine();
            if (ImGui.checkbox("Paused", paused)) {
                simulation.setPaused(paused.get());
            }

            if (ImGui.button("Extreme 1M RR")) {
                simulation.resetBoids(EXTREME_STRESS_COUNT, System.nanoTime());
                renderer.setRenderStride(8);
            }

            if (simulation.threadMode() == SystemThreadMode.DEDICATED_THREAD) {
                ImGui.text("Dedicated runtime active: world mutations are queued.");
            }
        }

        if (ImGui.collapsingHeader("Steering Weights")) {
            if (ImGui.sliderFloat("Separation", separationWeight, 0.0f, 4.0f, "%.2f")) {
                simulation.setSeparationWeight(separationWeight[0]);
            }
            if (ImGui.sliderFloat("Alignment", alignmentWeight, 0.0f, 3.0f, "%.2f")) {
                simulation.setAlignmentWeight(alignmentWeight[0]);
            }
            if (ImGui.sliderFloat("Cohesion", cohesionWeight, 0.0f, 3.0f, "%.2f")) {
                simulation.setCohesionWeight(cohesionWeight[0]);
            }
            if (ImGui.sliderFloat("Max Force", maxForce, 1.0f, 40.0f, "%.2f")) {
                simulation.setMaxForce(maxForce[0]);
            }
            if (ImGui.button("Reset Weights")) {
                simulation.resetSteeringParameters();
            }
        }

        if (ImGui.collapsingHeader("Stress And Render")) {
            if (ImGui.sliderInt("Render stride", renderStride, 1, 32)) {
                renderer.setRenderStride(renderStride[0]);
            }
            ImGui.text(String.format("Rendered points: %,d", renderer.lastVisibleBoidCount()));
            if (ImGui.button("Full Render")) {
                renderer.setRenderStride(1);
            }
            ImGui.sameLine();
            if (ImGui.button("Reduced 4x")) {
                renderer.setRenderStride(4);
            }
            ImGui.sameLine();
            if (ImGui.button("Reduced 8x")) {
                renderer.setRenderStride(8);
            }
        }

        if (ImGui.collapsingHeader("Camera")) {
            if (ImGui.checkbox("Auto Orbit", autoOrbit)) {
                cameraController.setAutoOrbit(autoOrbit.get());
            }
            if (ImGui.sliderFloat("Distance", cameraDistance, 40.0f, 1200.0f, "%.1f")) {
                cameraController.setDistance(cameraDistance[0]);
            }
            ImGui.text("Right mouse drag: orbit");
            ImGui.text("Mouse wheel: zoom");
            ImGui.text("F1: toggle overlay");
        }

        if (ImGui.collapsingHeader("Benchmark")) {
            ImGui.text(String.format("Status: %s", benchmarkRunner.phaseLabel()));
            ImGui.text(String.format("Progress: %.0f%%", benchmarkRunner.progress() * 100.0f));
            if (benchmarkRunner.isRunning()) {
                ImGui.text(String.format("Frames remaining: %d", benchmarkRunner.framesRemaining()));
            }

            if (ImGui.button("Quick Bench")) {
                benchmarkRunner.start(120, 240);
            }
            ImGui.sameLine();
            if (ImGui.button("Long Bench")) {
                benchmarkRunner.start(240, 600);
            }
            ImGui.sameLine();
            if (ImGui.button("Cancel Bench")) {
                benchmarkRunner.cancel();
            }

            if (benchmarkRunner.hasResult()) {
                BenchmarkRunner.BenchmarkResult result = benchmarkRunner.lastResult();
                ImGui.text(String.format("Last avg FPS: %.1f", result.averageFps()));
                ImGui.text(String.format("Last avg frame: %.3f ms", result.averageFrameMillis()));
                if (ImGui.button("Export CSV")) {
                    try {
                        benchmarkRunner.exportLastResult(java.nio.file.Path.of("benchmark-results"));
                    } catch (java.io.IOException exception) {
                        throw new RuntimeException("Failed to export benchmark CSV", exception);
                    }
                }
                String exportPath = benchmarkRunner.lastExportPath();
                if (!exportPath.isBlank()) {
                    ImGui.text(exportPath);
                }
            }
        }

        if (ImGui.collapsingHeader("Archetypes")) {
            for (WorldStatsCollector.ArchetypeSummary archetype : worldStats.archetypes()) {
                String label = String.format(
                    "Archetype %d | %,d entities | %d chunks",
                    archetype.ordinal(),
                    archetype.entityCount(),
                    archetype.chunkCount()
                );
                if (ImGui.treeNode(label)) {
                    ImGui.text(String.format("Components: %d", archetype.componentCount()));
                    ImGui.text(String.format("Chunk groups: %d", archetype.chunkGroupCount()));
                    ImGui.text(String.format("Occupancy: %.1f%%", archetype.occupancy() * 100.0f));
                    ImGui.treePop();
                }
            }
        }

        ImGui.end();
    }

    private static float max(float[] values) {
        float max = 0.0f;
        for (float value : values) {
            if (value > max) {
                max = value;
            }
        }
        return Math.max(max, 1.0f);
    }
}
