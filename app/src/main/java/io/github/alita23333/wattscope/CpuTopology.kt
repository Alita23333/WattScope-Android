package io.github.alita23333.wattscope

data class CpuCluster(val policy: Int, val cores: List<Int>) {
    val frequencyId
        get() = "cpu.cluster$policy.freq"

    val usageId
        get() = "cpu.cluster$policy.usage"

    val label: String
        get() =
            when {
                cores.isEmpty() -> "Cluster $policy"
                cores.size > 1 && cores.zipWithNext().all { (a, b) -> b == a + 1 } ->
                    "CPU ${cores.first()}–${cores.last()}"
                else -> "CPU " + cores.joinToString(", ")
            }
}

object CpuTopology {
    /** Match shared frequency-policy sources, never infer clusters from coincident clock values. */
    fun discover(metrics: Collection<Metric>): List<CpuCluster> {
        val cluster = Regex("cpu\\.cluster(\\d+)\\.freq")
        val core = Regex("cpu\\.(\\d+)\\.freq")
        return metrics
            .mapNotNull { policy ->
                val index =
                    cluster.matchEntire(policy.id)?.groupValues?.get(1)?.toIntOrNull()
                        ?: return@mapNotNull null
                val cores =
                    if (policy.source.isBlank()) emptyList()
                    else
                        metrics
                            .mapNotNull { metric ->
                                if (metric.source == policy.source)
                                    core.matchEntire(metric.id)?.groupValues?.get(1)?.toIntOrNull()
                                else null
                            }
                            .distinct()
                            .sorted()
                CpuCluster(index, cores)
            }
            .sortedBy { it.policy }
    }
}
