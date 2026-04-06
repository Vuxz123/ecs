package com.ethnicthv.ecs.boid.debug;

import com.ethnicthv.ecs.boid.render.BoidRenderer;
import com.ethnicthv.ecs.boid.sim.BoidSimulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class BenchmarkRunner {
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private Phase phase = Phase.IDLE;
    private int warmupFramesRemaining;
    private int sampleFramesRemaining;
    private int configuredWarmupFrames;
    private int configuredSampleFrames;
    private double accumulatedFrameSeconds;
    private double accumulatedSimMillis;
    private double accumulatedSteerMillis;
    private double accumulatedRenderMillis;
    private BenchmarkResult lastResult;
    private String lastExportPath = "";
    private List<SampleMeasurement> samples = List.of();

    public void start(int warmupFrames, int sampleFrames) {
        if (warmupFrames < 0 || sampleFrames <= 0) {
            throw new IllegalArgumentException("warmupFrames must be >= 0 and sampleFrames must be > 0");
        }
        configuredWarmupFrames = warmupFrames;
        configuredSampleFrames = sampleFrames;
        warmupFramesRemaining = warmupFrames;
        sampleFramesRemaining = sampleFrames;
        accumulatedFrameSeconds = 0.0;
        accumulatedSimMillis = 0.0;
        accumulatedSteerMillis = 0.0;
        accumulatedRenderMillis = 0.0;
        samples = new ArrayList<>(sampleFrames);
        phase = warmupFrames > 0 ? Phase.WARMUP : Phase.SAMPLING;
    }

    public void cancel() {
        phase = Phase.IDLE;
        warmupFramesRemaining = 0;
        sampleFramesRemaining = 0;
        samples = List.of();
    }

    public void update(double frameSeconds, BoidSimulation simulation, BoidRenderer renderer) {
        update(
            frameSeconds,
            simulation.getStats(),
            renderer.lastRenderMillis(),
            renderer.lastVisibleBoidCount(),
            renderer.renderStride()
        );
    }

    public void update(
        double frameSeconds,
        BoidSimulation.SimulationStats stats,
        double renderMillis,
        int visibleBoids,
        int renderStride
    ) {
        if (phase == Phase.IDLE || phase == Phase.COMPLETED) {
            return;
        }

        if (phase == Phase.WARMUP) {
            warmupFramesRemaining--;
            if (warmupFramesRemaining <= 0) {
                phase = Phase.SAMPLING;
            }
            return;
        }

        accumulatedFrameSeconds += frameSeconds;
        accumulatedSimMillis += stats.lastFixedStepMillis();
        accumulatedSteerMillis += stats.lastSteeringMillis();
        accumulatedRenderMillis += renderMillis;
        samples.add(new SampleMeasurement(
            configuredSampleFrames - sampleFramesRemaining,
            frameSeconds * 1_000.0,
            stats.lastFixedStepMillis(),
            stats.lastSteeringMillis(),
            renderMillis
        ));
        sampleFramesRemaining--;

        if (sampleFramesRemaining <= 0) {
            int sampledFrames = configuredSampleFrames;
            double averageFrameMillis = (accumulatedFrameSeconds / sampledFrames) * 1_000.0;
            double averageFps = accumulatedFrameSeconds <= 0.0 ? 0.0 : sampledFrames / accumulatedFrameSeconds;
            lastResult = new BenchmarkResult(
                LocalDateTime.now(),
                stats.boidCount(),
                visibleBoids,
                renderStride,
                stats.steeringExecutionMode().name(),
                configuredWarmupFrames,
                configuredSampleFrames,
                averageFps,
                averageFrameMillis,
                accumulatedSimMillis / sampledFrames,
                accumulatedSteerMillis / sampledFrames,
                accumulatedRenderMillis / sampledFrames,
                List.copyOf(samples)
            );
            phase = Phase.COMPLETED;
        }
    }

    public boolean isActive() {
        return phase == Phase.WARMUP || phase == Phase.SAMPLING;
    }

    public boolean isRunning() {
        return isActive();
    }

    public boolean hasResult() {
        return lastResult != null;
    }

    public BenchmarkResult lastResult() {
        return lastResult;
    }

    public String phaseLabel() {
        return phase.name();
    }

    public float progress() {
        if (phase == Phase.IDLE) {
            return 0.0f;
        }
        if (phase == Phase.COMPLETED) {
            return 1.0f;
        }
        if (phase == Phase.WARMUP) {
            return configuredWarmupFrames == 0 ? 1.0f : 1.0f - (warmupFramesRemaining / (float) configuredWarmupFrames);
        }
        return configuredSampleFrames == 0 ? 1.0f : 1.0f - (sampleFramesRemaining / (float) configuredSampleFrames);
    }

    public int framesRemaining() {
        return phase == Phase.WARMUP ? warmupFramesRemaining : sampleFramesRemaining;
    }

    public String lastExportPath() {
        return lastExportPath;
    }

    public String exportLastResult(Path directory) throws IOException {
        if (lastResult == null) {
            throw new IllegalStateException("No benchmark result available");
        }

        Files.createDirectories(directory);
        String filename = "boid-benchmark-" + FILE_TIMESTAMP.format(lastResult.timestamp()) + ".csv";
        Path file = directory.resolve(filename);
        Files.writeString(file, lastResult.toCsv());
        lastExportPath = file.toAbsolutePath().toString();
        return lastExportPath;
    }

    public enum Phase {
        IDLE,
        WARMUP,
        SAMPLING,
        COMPLETED
    }

    public record BenchmarkResult(
        LocalDateTime timestamp,
        int boidCount,
        int visibleBoids,
        int renderStride,
        String steeringMode,
        int warmupFrames,
        int sampleFrames,
        double averageFps,
        double averageFrameMillis,
        double averageSimMillis,
        double averageSteerMillis,
        double averageRenderMillis,
        List<SampleMeasurement> samples
    ) {
        public String toCsv() {
            StringBuilder csv = new StringBuilder();
            csv.append("section,timestamp,boid_count,visible_boids,render_stride,steering_mode,warmup_frames,sample_frames,avg_fps,avg_frame_ms,avg_sim_ms,avg_steer_ms,avg_render_ms,sample_index,frame_ms,sim_ms,steer_ms,render_ms")
                .append(System.lineSeparator());
            csv.append(String.format(
                Locale.ROOT,
                "summary,%s,%d,%d,%d,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,,,,,",
                timestamp,
                boidCount,
                visibleBoids,
                renderStride,
                steeringMode,
                warmupFrames,
                sampleFrames,
                averageFps,
                averageFrameMillis,
                averageSimMillis,
                averageSteerMillis,
                averageRenderMillis
            )).append(System.lineSeparator());

            for (SampleMeasurement sample : samples) {
                csv.append(String.format(
                    Locale.ROOT,
                    "sample,%s,%d,%d,%d,%s,%d,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%.3f,%.3f,%.3f,%.3f",
                    timestamp,
                    boidCount,
                    visibleBoids,
                    renderStride,
                    steeringMode,
                    warmupFrames,
                    sampleFrames,
                    averageFps,
                    averageFrameMillis,
                    averageSimMillis,
                    averageSteerMillis,
                    averageRenderMillis,
                    sample.sampleIndex(),
                    sample.frameMillis(),
                    sample.simMillis(),
                    sample.steerMillis(),
                    sample.renderMillis()
                )).append(System.lineSeparator());
            }
            return csv.toString();
        }
    }

    public record SampleMeasurement(
        int sampleIndex,
        double frameMillis,
        double simMillis,
        double steerMillis,
        double renderMillis
    ) {
    }
}
