# ECS Roadmap

Updated at the end of every milestone. Use this doc for historical context; architecture docs link here instead of duplicating change logs.

## Completed Milestones
### Phase 1 *(see `PHASE1_COMPLETION.md`)*
- Bootstrapped the original ECS experiment, including the first ComponentManager prototype and SoA research spikes.
- Demoed annotation-driven handles plus baseline QA/QC harnesses.

### Phase 2 *(see `PHASE2_COMPLETION.md`)*
- Finalized ArchetypeWorld with True SoA layout, SIMD-friendly query API, and builder-driven facade.
- Integrated the fixed-step `GameLoop`, Maven Central publish flow, and full QA/QC automation.

## Current Milestone *(Q4 2025)*
- Ship the refreshed ECS facade (run/stop/createGameLoop) and documentation suite (README, Usage, Advanced, Architecture, Roadmap).
- Harden publishing on Windows (SonatypeHost import, URI normalization) and verify `GeneratedComponents` auto-registration in mixed IDE setups.
- Outline contributor guidelines for the new docs stack.

## Rolling Backlog
1. **Parallel Execution**
   - Expose per-system parallel batch sizing (see `README_PARALLEL_SYSTEM.md`).
   - Benchmark multi-threaded query scheduling under heavy entity counts.
2. **Component Tooling**
   - Hot-reload hooks for editor integrations.
   - Visualization of archetype chunk occupancy.
3. **Gameplay-Level Features**
   - Event bus for component add/remove notifications.
   - Prefab/blueprint authoring pipeline backed by the processor.
4. **Observability**
   - Metrics export (frame timings, group deltas, allocation stats).
   - Trace hooks for profiling parallel pipelines.

> To propose new work, open an issue referencing this roadmap and the relevant appendix in `docs/ADVANCED_GUIDE.md`. Update this file whenever a milestone starts or ends, then re-evaluate backlog priorities.

