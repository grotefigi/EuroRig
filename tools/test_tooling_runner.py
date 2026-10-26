import io
import unittest

from run_tooling_tests import run_suite


class ToolingRunnerTests(unittest.TestCase):
    def test_pass_failure_error_skip_and_empty(self):
        def fail():
            raise AssertionError('control')

        def error():
            raise RuntimeError('control')

        def skip():
            raise unittest.SkipTest('control')

        for action, expected in ((lambda: None, 0), (fail, 1), (error, 1), (skip, 1)):
            with self.subTest(action=action.__name__):
                suite = unittest.TestSuite([unittest.FunctionTestCase(action)])
                self.assertEqual(expected, run_suite(suite, stream=io.StringIO()))
        self.assertEqual(1, run_suite(unittest.TestSuite(), stream=io.StringIO()))
