import unittest
from power_stats import cpu_zones, integrate, summarize


def sample(ns, e, enabled=True):
    return {"boot_ns": ns, "energy_uj": e, "enabled": enabled}


class PowerTruthTests(unittest.TestCase):
    def test_energy_not_instantaneous_or_utilization(self):
        metadata = {"interval_ms": 500, "zones": [
            {"path": "/soc", "parent": "", "name": "soc"},
            {"path": "/soc/m", "parent": "/soc", "name": "cpu-m"},
            {"path": "/soc/l", "parent": "/soc", "name": "cpu-l"},
            {"path": "/soc/debug", "parent": "/soc", "name": "debug-2"}]}
        data = [sample(0, [0, 0, 0, 0]), sample(500_000_000, [9999999, 100000, 50000, 999999])]
        result = summarize(metadata, data)
        self.assertEqual(result["cpu_names"], ["cpu-m", "cpu-l"])
        self.assertAlmostEqual(result["cpu_total"]["mean_power_mw"], 300)

    def test_missing_reset_gap_and_disabled_do_not_bridge(self):
        data = [sample(0, [0]), sample(500_000_000, [500000]),
                sample(1_000_000_000, [None]), sample(1_500_000_000, [1500000]),
                sample(2_000_000_000, [1]), sample(4_000_000_000, [500000]),
                sample(4_500_000_000, [1000000], False)]
        result = integrate(data, [0], 500)
        self.assertAlmostEqual(result["mean_power_mw"], 1000)
        self.assertAlmostEqual(result["valid_seconds"], .5)
        self.assertEqual(result["counter_resets"], 1)
        self.assertEqual(result["gaps"], 1)
        self.assertEqual(result["missing_intervals"], 3)

    def test_zero_is_valid(self):
        result = integrate([sample(0, [2, 9]), sample(500_000_000, [2, 9])], [0, 1], 500)
        self.assertEqual(result["mean_power_mw"], 0)
        self.assertEqual(result["valid_coverage"], 1)

    def test_parent_and_child_are_not_added(self):
        self.assertEqual(cpu_zones([
            {"name": "cpu", "path": "/cpu", "parent": "/"},
            {"name": "cpu-m", "path": "/cpu/m", "parent": "/cpu"}]), [0])

    def test_unknown_and_non_sibling_cpu_domains_refused(self):
        with self.assertRaises(ValueError):
            cpu_zones([{"name": "debug-2", "path": "/debug", "parent": "/"}])
        with self.assertRaises(ValueError):
            cpu_zones([{"name": "cpu-m", "path": "/a/m", "parent": "/a"},
                       {"name": "cpu-l", "path": "/b/l", "parent": "/b"}])


if __name__ == "__main__":
    unittest.main()
