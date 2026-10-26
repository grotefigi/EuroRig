"""Run the required tooling suite; skipped checks fail acceptance."""
import importlib.metadata
from pathlib import Path
import sys
import unittest


def run_suite(suite, stream=None):
    result = unittest.TextTestRunner(stream=stream, verbosity=1).run(suite)
    return 0 if result.wasSuccessful() and not result.skipped and result.testsRun else 1


def main():
    if importlib.metadata.version('osmium') != '4.3.1':
        raise RuntimeError('Install tools/test-requirements.txt before verification')
    directory = str(Path(__file__).resolve().parent)
    return run_suite(unittest.defaultTestLoader.discover(directory, pattern='test_*.py'))


if __name__ == '__main__':
    sys.exit(main())
