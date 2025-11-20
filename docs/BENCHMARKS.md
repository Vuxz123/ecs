# ECS Benchmarks (Snapshot)

This page highlights **representative measurements** of this ECS on a single machine.
The goal is to show how it behaves at scale, how well parallel queries use your cores,
and how much you gain by batching structural changes with `EntityCommandBuffer`.

All numbers below come from a single run (`results-11-52-14-11-2025.txt`).
They characterize **this implementation** – they are not meant for cross-engine comparisons.

---

## 1. High-Level Takeaways

On the reference machine used for these runs:

- **Comfortable with high entity counts.** Creating **1,000,000 entities** completes in about **151 ms**.
- **Queries scale linearly and parallelize well.** Large sequential queries over **1,000,000 entities** take ~**23.6 ms**, while the parallel version of the same workload drops to about **6.2 ms** (≈ **3.8× faster**).
- **Hot query loops stay in the low milliseconds.** Read/write update passes over **1,000,000 entities** complete in roughly **13–15 ms**, suitable for tight simulation loops depending on your frame budget.
- **Batching structural changes is a big win.** At **100,000 entities**, batched add/remove patterns are about **1.6–1.7× faster** than per-entity calls.
- **EntityCommandBuffer shines in heavy mutation scenarios.** In `EntityCommandBufferBenchmark`, batched mutation can be over **10× faster** than per-entity mutations at **10,000 entities**.

These numbers are not theoretical – they are measured from the actual `ecs-benchmark` module
running against the same APIs exposed to users.

---

## 2. Scaling with Entity Count

### 2.1 Entity Creation

Average time to create a batch of entities (`ComponentManagerBenchmark.entityCreation`):

| Entity count | Avg time (ms/op) | Notes                          |
|-------------:|------------------:|--------------------------------|
|          10  |             0.005 | negligible, essentially free   |
|         100  |             0.016 | still negligible               |
|       1,000  |             0.135 | well below 1 ms                |
|      10,000  |             1.270 | stays very small               |
|     100,000  |            17.625 | tens of ms on this machine     |
|   1,000,000  |           151.183 | ~0.15 s burst at one million   |

What this says about the ECS:

- The archetype + SoA data model lets you **scale to hundreds of thousands of entities**
  without pathological slowdowns.
- Even at one million entities, a large-creation burst is still on the order of a
  single frame on a 60 Hz game (and can be amortized or streamed if needed).

### 2.2 Query Iteration (Read/Write & Update)

Average time per operation for full-world queries (`ComponentManagerBenchmark.queryIteration_*`):

| Benchmark                                      | Entities   | Avg time (ms/op) |
|-----------------------------------------------|-----------:|------------------:|
| `queryIteration_ReadWrite`                    |    100,000 |             1.501 |
| `queryIteration_ReadWrite`                    |  1,000,000 |            15.285 |
| `queryIteration_Update` (read + write update) |    100,000 |             1.478 |
| `queryIteration_Update`                       |  1,000,000 |            13.816 |

Takeaway:

- Full read/write passes over **100k–1M entities** stay in the **low millisecond to low tens of ms** range.
- Combined with selective queries and system grouping, this gives plenty of headroom
  for real game/simulation workloads.

---

## 3. Parallel Queries: Turning Cores into Headroom

The `ComponentManagerBenchmark.largeScale_*Query` benchmarks measure a simple but heavy
pattern: scan a large world and touch required components.

### 3.1 Sequential vs Parallel

From `ComponentManagerBenchmark.largeScale_SequentialQuery` and
`ComponentManagerBenchmark.largeScale_ParallelQuery`:

| Entities   | Sequential (ms/op) | Parallel (ms/op) | Approx speedup |
|-----------:|--------------------:|------------------:|----------------:|
|    10,000  |               0.207 |             0.116 |        ~1.8×   |
|   100,000  |               2.020 |             0.867 |        ~2.3×   |
| 1,000,000  |              23.640 |             6.233 |        ~3.8×   |

On this machine, large, hot queries see **2–4× throughput gains** when moved from
sequential to parallel execution.

Why this matters:

- Parallel queries reuse the same **archetype + chunk layout**, so you keep the
  same cache-friendly access pattern while putting more cores to work.
- The ECS lets you opt into parallel execution for systems that are naturally
  data-parallel and thread-safe, without changing your component layout.

---

## 4. Structural Changes: Batching vs Per-Entity

Changing archetypes (add/remove components, move entities between layouts) is
inherently more expensive than pure reads. The benchmarks show why this ECS
leans hard on **batched structural operations**.

### 4.1 BatchStructuralMoveBenchmark

Average time to add/remove components in bulk (`BatchStructuralMoveBenchmark`):

| Operation                    | Entities   | Batch (ms/op) | Per-entity (ms/op) | Approx speedup |
|-----------------------------|-----------:|---------------:|--------------------:|----------------:|
| `add_*` components          |    10,000  |          2.084 |               3.933 |        ~1.9×   |
| `add_*` components          |   100,000  |         28.843 |              45.194 |        ~1.6×   |
| `remove_*` components       |    10,000  |          1.924 |               3.084 |        ~1.6×   |
| `remove_*` components       |   100,000  |         22.986 |              39.099 |        ~1.7×   |

Interpretation:

- When you add/remove components **in batches**, the ECS can reorganize chunks and
  migrate entities more efficiently.
- Per-entity changes remain supported, but the architecture is clearly optimized
  for **batched structural edits**.

### 4.2 EntityCommandBufferBenchmark

`EntityCommandBufferBenchmark` focuses on the preferred way to perform heavy
structural changes: queue them, then apply in a controlled phase.

| Scenario                         | Entities   | ECB batch (ns/op) | Per-entity (ns/op) | Approx speedup |
|----------------------------------|-----------:|-------------------:|--------------------:|----------------:|
| `mutate_*` many entities         |    10,000  |           284,330 |           3,042,782 |       ~10.7×   |
| `shared_*` shared-component ops  |    10,000  |         1,039,413 |           3,715,792 |        ~3.6×   |

The numbers speak for themselves:

- For heavy mutation workloads, routing changes through an **`EntityCommandBuffer`**
  unlocks **order-of-magnitude** gains on this machine.
- This is why the advanced docs strongly recommend that structural changes –
  especially when combined with `ExecutionMode.PARALLEL` – go through
  `EntityCommandBuffer` instead of direct per-entity calls in hot loops.

---

## 5. Throughput Snapshots

For a quick sense of raw throughput, the benchmarks also report operations per second
(`ComponentManagerBenchmark.throughput_EntitiesPerSecond` and related tests):

| Benchmark                                       | Entities   | Score (approx) | Units |
|------------------------------------------------|-----------:|---------------:|:------|
| `throughput_EntitiesPerSecond`                 |   100,000  |          314.8 | ops/s |
| `throughput_EntitiesPerSecond`                 | 1,000,000  |           27.1 | ops/s |
| `throughput_MaximalIteration`                  |   100,000  |            0.98| ms/op |
| `throughput_MaximalIteration`                  | 1,000,000  |           16.47| ms/op |

Use these as **order-of-magnitude** indicators: they confirm that the ECS behaves
smoothly from tens of thousands up to a million entities in typical benchmark setups.

---

## 6. How to Read These Numbers

- All results come from a **single machine, single configuration, single JDK**.
- The goal is to show how this ECS behaves **internally** as you scale entity counts,
  use parallel queries, and rely on batched structural changes.
- They are **not** intended to be compared directly against other engines or libraries.

If you need to reproduce or extend these results on your own hardware, see the
`ecs-benchmark` module and the notes in `docs/ADVANCED_GUIDE.md`.

