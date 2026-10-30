"""Pure benchmark evidence classification, no HTTP, subprocess or database calls."""
import importlib.util
from pathlib import Path
import unittest

MODULE = Path(__file__).resolve().parents[1] / 'teaching_performance.py'
spec = importlib.util.spec_from_file_location('teaching_performance_subject', MODULE)
performance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(performance)


class PerformanceEvidenceTest(unittest.TestCase):
    def test_server_errors_without_a_known_id_remain_unresolved_creations(self):
        # A proxy error or response serialization failure cannot prove rollback.
        for status in (500, 502, 503, 504, None, 200):
            with self.subTest(status=status):
                self.assertTrue(performance.creation_outcome_unknown(status, None))
        self.assertFalse(performance.creation_outcome_unknown(409, None))
        self.assertFalse(performance.creation_outcome_unknown(400, None))
        # A known ID is handled by the real cleanup pass even with a bad status.
        self.assertFalse(performance.creation_outcome_unknown(500, 41))
        self.assertTrue(performance.creation_outcome_unknown(503, True))

    def test_nearest_rank_uses_all_twenty_attempts_and_failure_cannot_be_accepted(self):
        samples = [{'status': 'success', 'elapsed_ms': value} for value in range(1, 21)]
        result = performance.summary(samples)
        self.assertEqual(result['p95_nearest_rank_ms_all_attempts'], 19)
        self.assertEqual(result['attempted_samples'], 20)
        self.assertTrue(result['target_met'])
        samples[-1] = {'status': 'failure', 'elapsed_ms': 9000}
        result = performance.summary(samples)
        self.assertEqual(result['attempted_samples'], 20)
        self.assertEqual(result['successful_samples'], 19)
        self.assertEqual(result['failed_samples'], 1)
        self.assertEqual(result['maximum_ms_all_attempts'], 9000)
        self.assertFalse(result['target_met'])


if __name__ == '__main__':
    unittest.main()
