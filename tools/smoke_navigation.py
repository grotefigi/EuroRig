"""Compatibility entry point for actual native route/GPS tests on a dedicated emulator."""
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).with_name('smoke_native.py')),run_name='__main__')
