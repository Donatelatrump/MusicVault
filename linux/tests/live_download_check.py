"""Opt-in network regression: download a short public test clip and play its local file twice."""
import argparse
import json
import os
from pathlib import Path
import tempfile
import threading
import time
import sys

sys.path.insert(0,str(Path(__file__).resolve().parents[1]))


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument("--url",required=True)
    args=parser.parse_args()
    os.environ.setdefault("QT_QPA_PLATFORM","offscreen")
    from PyQt6.QtWidgets import QApplication
    from PyQt6.QtTest import QTest
    from musicvault.models import Track
    from musicvault.database import Database
    from musicvault.player import Player
    from musicvault.services import download
    with tempfile.TemporaryDirectory(prefix="musicvault-live-download-") as folder:
        os.environ["MUSICVAULT_DATA_DIR"]=folder
        progress=[]
        remote=Track("youtube:test-clip","Public test clip",source="youtube",url=args.url)
        result=download(remote,Path(folder)/"audio",threading.Event(),progress.append)
        db=Database();db.save_track(result);db.set_download(result,"complete",100)
        app=QApplication([]);player=Player(db,audio_output="null");requests=[]
        player.need_track.connect(requests.append)
        try:
            for _ in range(2):
                player.play_queue([remote])
                deadline=time.monotonic()+5
                while not player.loaded and time.monotonic()<deadline:QTest.qWait(50)
                if not player.loaded:raise RuntimeError("El archivo descargado no inició la reproducción.")
                player.stop()
            assert not requests
            print(json.dumps({"downloaded":True,"bytes":Path(result.path).stat().st_size,
                              "duration_seconds":result.duration,"progress_events":len(progress),
                              "local_playbacks":2,"repeat_download_requests":len(requests)}))
        finally:
            player.close()


if __name__=="__main__":main()
