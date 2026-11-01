"""Pipe reader that also works on Windows, where select cannot watch pipes."""
import os
import queue
import signal
import subprocess
import threading


def process_options():
    return {"creationflags": subprocess.CREATE_NO_WINDOW} if os.name == "nt" else {"start_new_session": True}


def stop_process(process, force=False, timeout=30):
    if process.poll() is not None:
        return
    if os.name == "nt":
        # Include FFmpeg children, otherwise cancellation leaves files locked.
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                       creationflags=subprocess.CREATE_NO_WINDOW, timeout=timeout)
    else:
        try:
            os.killpg(process.pid, signal.SIGKILL if force else signal.SIGTERM)
        except ProcessLookupError:
            pass


class PipeSelector:
    def __init__(self):
        self.chunks = queue.Queue()
        self.pending = b""
        self.thread = None

    def register(self, pipe, events):
        def read():
            try:
                while True:
                    chunk = os.read(pipe.fileno(), 8192)
                    self.chunks.put(chunk)
                    if not chunk:
                        break
            except (OSError, ValueError):
                self.chunks.put(b"")
        self.thread = threading.Thread(target=read, daemon=True)
        self.thread.start()

    def select(self, timeout):
        try:
            self.pending = self.chunks.get(timeout=timeout)
            return True
        except queue.Empty:
            return False

    def read(self):
        return self.pending

    def close(self):
        pass  # The producer exits at EOF after the subprocess is terminated.
