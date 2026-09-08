"""Synthetic comparison of the previous cell-allocation loop and the virtual table.
Run: /usr/bin/python3 linux/tests/benchmark_render.py
Measures table update only, not database queries, network or end-to-end latency.
"""
import os, sys, tempfile, time, statistics, json
from pathlib import Path
os.environ['QT_QPA_PLATFORM']='offscreen'
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from PyQt6.QtWidgets import QApplication, QTableWidget, QTableWidgetItem, QHeaderView
from musicvault.track_model import TrackModel, TrackTable
from musicvault.widgets import icon, TrackDelegate, load_fonts
from musicvault.models import Track, clock_text
app=QApplication([]);load_fonts()
with tempfile.NamedTemporaryFile() as audio:
    tracks=[Track(str(i),f'Canción {i}','Artista','Álbum',180,path=audio.name) for i in range(10000)]
    legacy=QTableWidget(0,6);legacy.resize(1000,650);legacy.verticalHeader().hide();legacy.setColumnHidden(2,True);legacy.setColumnHidden(3,True);legacy.setItemDelegateForColumn(1,TrackDelegate(legacy));legacy.show()
    modern=TrackTable();modern.resize(1000,650);model=TrackModel(modern);modern.setModel(model);modern.verticalHeader().setDefaultSectionSize(60);modern.verticalHeader().setSectionResizeMode(QHeaderView.ResizeMode.Fixed);modern.setItemDelegateForColumn(1,TrackDelegate(modern));modern.show();app.processEvents()
    times={'previous_ms':[],'model_ms':[]}
    for _ in range(3):
        start=time.perf_counter();legacy.setUpdatesEnabled(False);legacy.setRowCount(len(tracks))
        for row,track in enumerate(tracks):
            local=bool(track.path and Path(track.path).is_file())
            for column,value in enumerate(['',track.title,track.artist,track.album,clock_text(track.duration),'Descargada' if local else 'En línea']):
                item=QTableWidgetItem(value);item.setToolTip(value);legacy.setItem(row,column,item)
            legacy.item(row,0).setIcon(icon('album','#B3A992',48));legacy.setRowHeight(row,72)
        legacy.setUpdatesEnabled(True);app.processEvents();times['previous_ms'].append((time.perf_counter()-start)*1000)
        start=time.perf_counter();model.set_tracks(tracks,{}, {},None);app.processEvents();times['model_ms'].append((time.perf_counter()-start)*1000)
    print(json.dumps({'tracks':len(tracks),'passes':3,'previous_median_ms':round(statistics.median(times['previous_ms']),1),'model_median_ms':round(statistics.median(times['model_ms']),1),'samples':times},indent=2))
