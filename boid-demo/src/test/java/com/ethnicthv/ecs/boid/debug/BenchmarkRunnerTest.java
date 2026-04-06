package com.ethnicthv.ecs.boid.debug;

import com.ethnicthv.ecs.boid.render.BoidRenderer;
import com.ethnicthv.ecs.boid.sim.BoidSimulation;
import com.ethnicthv.ecs.boid.sim.SimulationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunnerTest {
    @Test
    void benchmarkRunnerProducesResultAndExportsCsv(@TempDir Path tempDir) throws IOException {
        SimulationConfig config = SimulationConfig.defaultConfig()
            .withInitialBoidCount(16)
            .withRandomSeed(77L);
        BenchmarkRunner benchmarkRunner = new BenchmarkRunner();

        try (BoidSimulation simulation = new BoidSimulation(config)) {
            simulation.bootstrap();
            BoidRenderer renderer = new BoidRenderer();
            renderer.setRenderStride(2);

            benchmarkRunner.start(1, 2);
            simulation.stepFixed();
            benchmarkRunner.update(1.0 / 60.0, simulation, renderer);
            simulation.stepFixed();
            benchmarkRunner.update(1.0 / 60.0, simulation, renderer);
            simulation.stepFixed();
            benchmarkRunner.update(1.0 / 60.0, simulation, renderer);

            assertTrue(benchmarkRunner.hasResult());
            String exportPath = benchmarkRunner.exportLastResult(tempDir);
            assertFalse(exportPath.isBlank());
            assertTrue(Files.exists(Path.of(exportPath)));
            assertTrue(Files.readString(Path.of(exportPath)).contains("avg_fps"));
            assertTrue(Files.readString(Path.of(exportPath)).contains("sample,"));
        }
    }
}
