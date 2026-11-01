"""KataBump entry point to verify tools before configuring the Discord token."""
import runpy
import sys

sys.argv = ["main.py", "--check"]
runpy.run_path("main.py", run_name="__main__")
