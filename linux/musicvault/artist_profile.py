"""Artist profiles using Android's Deezer artist IDs, top tracks and discography."""
from PyQt6.QtCore import Qt, QSize, QTimer
from PyQt6.QtGui import QIcon
from PyQt6.QtWidgets import (QDialog, QVBoxLayout, QHBoxLayout, QLabel, QPushButton,
                            QLineEdit, QTabWidget, QWidget, QListWidget, QListWidgetItem,
                            QAbstractItemView, QHeaderView, QMenu)
from .artist_portraits import select_artist, portrait
from .services import get_json, deezer_track
from .track_model import TrackModel, TrackTable
from .widgets import Cover, TrackDelegate, icon


class ArtistProfileService:
    def __init__(self, database):
        self.db = database

    def _get(self, path, **params):
        key = 'artist-profile:v1:' + path + ':' + repr(sorted(params.items()))
        cached = self.db.cache_get(key, ttl=86400)
        if cached is not None:
            return cached
        try:
            result = get_json('https://api.deezer.com/'+path, **params)
            if result.get('error'):
                raise RuntimeError('El catálogo de este artista no está disponible. Reintenta.')
            self.db.cache_put(key, result)
            return result
        except Exception:
            stale = self.db.cache_get(key, ttl=None)
            if stale is not None:
                return stale
            raise

    def artists(self, name):
        rows = self._get('search/artist', q=name, limit=50).get('data', [])
        exact = select_artist(rows, name)
        return [exact] if exact else rows

    @staticmethod
    def artist_id(artist):
        value = str(artist['id'])
        if not value.isdigit():
            raise ValueError('El identificador del artista no es válido.')
        return value

    def tracks(self, artist, query=''):
        artist_id = self.artist_id(artist)
        if query.strip():
            name = artist['name'].replace('"','')
            title = query.replace('"','')
            rows = self._get('search', q=f'{name} {title}', limit=50).get('data', [])
        else:
            rows = self._get(f'artist/{artist_id}/top', limit=50).get('data', [])
        return [deezer_track(row) for row in rows if str(row.get('artist',{}).get('id')) == artist_id]

    def albums(self, artist, offset=0):
        artist_id = self.artist_id(artist)
        payload = self._get(f'artist/{artist_id}/albums', limit=50, index=offset)
        rows = payload.get('data', [])
        albums = [{'id':str(row['id']), 'title':row.get('title',''), 'artist':artist['name'],
                   'source':'deezer', 'cover':next((row.get(k) for k in ('cover_xl','cover_big','cover_medium','cover') if row.get(k)), ''),
                   'year':row.get('release_date','')[:4]} for row in rows]
        more = offset+len(rows) < payload['total'] if 'total' in payload else bool(payload.get('next')) or len(rows)==50
        return albums, more, offset+len(rows)


class ArtistProfileDialog(QDialog):
    def __init__(self, window):
        super().__init__(window)
        self.window = window
        self.service = ArtistProfileService(window.db)
        self.generation = self.track_generation = 0
        self.artist = None
        self.tracks = []
        self.offset = 0
        self.album_loading = False
        self.closed = False
        self.setWindowTitle('Perfil del artista')
        self.resize(1000, 760)
        self.setMinimumSize(720, 540)
        outer = QVBoxLayout(self)
        header = QHBoxLayout()
        self.photo = Cover(100);header.addWidget(self.photo)
        text = QVBoxLayout()
        self.title = QLabel();self.title.setObjectName('heading');self.title.setTextFormat(Qt.TextFormat.PlainText)
        self.details = QLabel();self.details.setTextFormat(Qt.TextFormat.PlainText)
        text.addWidget(self.title);text.addWidget(self.details);header.addLayout(text,1)
        outer.addLayout(header)
        self.status = QLabel();self.status.setWordWrap(True);self.status.setTextFormat(Qt.TextFormat.PlainText);outer.addWidget(self.status)
        self.retry = QPushButton('Reintentar');self.retry.clicked.connect(self.retry_load);self.retry.hide();outer.addWidget(self.retry)
        self.choices = QListWidget();self.choices.hide();outer.addWidget(self.choices)
        self.choices.itemClicked.connect(lambda item:self.select_artist(item.data(Qt.ItemDataRole.UserRole)))
        self.tabs = QTabWidget();outer.addWidget(self.tabs,1)
        self.tabs.setStyleSheet('QTabWidget::pane {border:0;} QTabBar::tab {background:#211D17;color:#B3A992;padding:10px 24px;} QTabBar::tab:selected {background:#382C1C;color:#F1EAD9;}')
        songs = QWidget();layout = QVBoxLayout(songs)
        tools = QHBoxLayout()
        self.back = QPushButton('← Canciones del artista');self.back.hide();self.back.clicked.connect(self.top_tracks);tools.addWidget(self.back)
        play = QPushButton('Reproducir');play.setObjectName('primary');play.clicked.connect(lambda:self.play(0));tools.addWidget(play)
        self.search = QLineEdit();self.search.setPlaceholderText('Buscar una canción de este artista');tools.addWidget(self.search,1)
        find = QPushButton('Buscar');find.clicked.connect(self.search_tracks);tools.addWidget(find);self.search.returnPressed.connect(self.search_tracks)
        layout.addLayout(tools)
        self.model = TrackModel(self)
        self.table = TrackTable();self.table.setModel(self.model)
        self.table.setItemDelegateForColumn(1,TrackDelegate(self.table))
        self.table.setSelectionBehavior(QAbstractItemView.SelectionBehavior.SelectRows)
        self.table.setEditTriggers(QAbstractItemView.EditTrigger.NoEditTriggers)
        self.table.verticalHeader().hide();self.table.verticalHeader().setDefaultSectionSize(60)
        self.table.horizontalHeader().setSectionResizeMode(1,QHeaderView.ResizeMode.Stretch)
        self.table.setColumnWidth(0,35);self.table.setColumnWidth(2,160);self.table.setColumnWidth(3,160)
        self.table.setColumnWidth(4,70);self.table.setColumnWidth(5,100)
        self.table.doubleClicked.connect(lambda index:self.play(index.row()))
        self.table.clicked.connect(lambda index:self.play(index.row()) if index.column()==0 else None)
        self.table.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu)
        self.table.customContextMenuRequested.connect(self.track_menu)
        self.table.visible_rows_changed.connect(self.load_track_covers)
        layout.addWidget(self.table,1)
        actions=QHBoxLayout()
        download=QPushButton('Descargar seleccionada');download.clicked.connect(lambda:self.selected_action(self.window.start_download));actions.addWidget(download)
        queue=QPushButton('Añadir a cola');queue.clicked.connect(lambda:self.selected_action(self.window.player.append));actions.addWidget(queue)
        favorite=QPushButton('Me gusta / Quitar Me gusta');favorite.clicked.connect(lambda:self.selected_action(self.window.favorite));actions.addWidget(favorite)
        actions.addStretch();layout.addLayout(actions);self.tabs.addTab(songs,'Canciones')
        releases=QWidget();layout=QVBoxLayout(releases)
        self.albums=QListWidget();self.albums.setIconSize(QSize(72,72));self.albums.setUniformItemSizes(True)
        self.albums.itemClicked.connect(self.open_album);layout.addWidget(self.albums,1)
        self.more=QPushButton('Cargar más álbumes');self.more.clicked.connect(self.load_albums);layout.addWidget(self.more)
        self.albums.verticalScrollBar().valueChanged.connect(self.load_album_covers)
        self.tabs.currentChanged.connect(lambda _:QTimer.singleShot(0,self.load_album_covers))
        self.tabs.addTab(releases,'Álbumes')
        self.finished.connect(self.finish)
        self.window.player.track_changed.connect(lambda _: self.refresh_tracks() if not self.closed else None)

    def finish(self):
        self.closed = True
        self.generation += 1

    def error(self, error):
        self.status.setText(str(error));self.retry.show()

    def show_artist(self, name):
        self.closed = False
        self.generation += 1
        generation = self.generation
        self.requested_name = name
        self.artist = None;self.tracks=[];self.albums.clear();self.refresh_tracks()
        self.photo.load('');self.title.setText(name);self.details.clear()
        self.choices.clear();self.choices.hide();self.retry.hide();self.tabs.setEnabled(False)
        self.status.setText('Buscando el perfil del artista…')
        self.show();self.raise_();self.activateWindow()
        def ready(rows):
            if self.closed or generation!=self.generation:return
            if len(rows)==1:
                self.select_artist(rows[0]);return
            self.status.setText('Selecciona el artista correcto.' if rows else 'No se encontró un perfil para este artista.')
            self.choices.setVisible(bool(rows))
            for artist in rows:
                item=QListWidgetItem(artist['name']);item.setData(Qt.ItemDataRole.UserRole,artist);self.choices.addItem(item)
        self.window.jobs.submit(lambda cancel,progress:self.service.artists(name),ready,
                                lambda e:self.error(e) if not self.closed and generation==self.generation else None)

    def select_artist(self, artist):
        self.generation += 1
        generation = self.generation
        self.artist=artist;self.offset=0;self.album_loading=False;self.albums.clear();self.choices.hide()
        self.title.setText(artist['name']);self.details.setText(f"{artist.get('nb_fan',0):,} seguidores")
        self.status.clear();self.retry.hide();self.tabs.setEnabled(True);self.tabs.setCurrentIndex(0)
        def photo_ready(path):
            if not self.closed and generation==self.generation:self.photo.load(path)
        self.window.jobs.submit(lambda cancel,progress:portrait(artist['name'],cancel),photo_ready,lambda _:None)
        self.top_tracks();self.load_albums()

    def retry_load(self):
        if self.artist:self.select_artist(self.artist)
        else:self.show_artist(self.requested_name)

    def top_tracks(self):
        self.search.clear();self.back.hide();self.load_tracks()

    def search_tracks(self):
        self.back.hide();self.load_tracks(self.search.text())

    def load_tracks(self, query=''):
        if not self.artist:return
        self.track_generation+=1
        generation, request, artist=self.generation,self.track_generation,self.artist
        self.status.setText('Cargando canciones…');self.retry.hide()
        self.window.jobs.submit(lambda cancel,progress:self.service.tracks(artist,query),
                                lambda tracks:self.tracks_ready(tracks,generation,request),
                                lambda e:self.error(e) if generation==self.generation and request==self.track_generation and not self.closed else None)

    def tracks_ready(self, tracks, generation, request):
        if self.closed or generation!=self.generation or request!=self.track_generation:return
        self.tracks=tracks;self.refresh_tracks()
        self.status.setText('' if tracks else 'No se encontraron canciones.')

    def refresh_tracks(self):
        current=self.window.player.current
        records={row['track_id']:row for row in self.window.db.downloads()}
        self.model.set_tracks(self.tracks,records,self.window.download_jobs,current.id if current else None)
        QTimer.singleShot(0,self.load_track_covers)

    def play(self, index):
        if 0<=index<len(self.tracks):self.window.player.play_queue(self.tracks,index)

    def selected_action(self, callback):
        row=self.table.currentIndex().row()
        if 0<=row<len(self.tracks):callback(self.tracks[row])

    def track_menu(self, point):
        row=self.table.rowAt(point.y())
        if row<0 or row>=len(self.tracks):return
        track=self.tracks[row]
        menu=QMenu(self)
        menu.addAction('Reproducir',lambda:self.play(row))
        menu.addAction('Añadir a la cola',lambda:self.window.player.append(track))
        menu.addAction('Descargar',lambda:self.window.start_download(track))
        menu.addAction('Quitar de favoritos' if track.id in self.window.db.favorites() else 'Añadir a favoritos',lambda:self.window.favorite(track))
        menu.exec(self.table.viewport().mapToGlobal(point))

    def load_albums(self):
        if not self.artist or self.album_loading:return
        self.album_loading=True;self.more.setEnabled(False);self.more.setText('Cargando álbumes…')
        generation,artist=self.generation,self.artist
        def ready(page):
            if self.closed or generation!=self.generation:return
            rows,more,self.offset=page
            self.album_loading=False
            known={self.albums.item(i).data(Qt.ItemDataRole.UserRole)['id'] for i in range(self.albums.count())}
            for album in rows:
                if album['id'] in known:continue
                known.add(album['id'])
                item=QListWidgetItem(album['title']+'\n'+album['year']);item.setSizeHint(QSize(220,86))
                item.setData(Qt.ItemDataRole.UserRole,album);self.albums.addItem(item)
            self.more.setEnabled(more);self.more.setText('Cargar más álbumes' if more else 'Todos los álbumes cargados')
            self.tabs.setTabText(1,f'Álbumes ({self.albums.count()})');self.load_album_covers()
        def failed(e):
            if self.closed or generation!=self.generation:return
            self.album_loading=False;self.more.setEnabled(True);self.more.setText('Reintentar álbumes');self.error(e)
        offset=self.offset
        self.window.jobs.submit(lambda cancel,progress:self.service.albums(artist,offset),ready,failed)

    def open_album(self, item):
        album=item.data(Qt.ItemDataRole.UserRole)
        self.track_generation+=1
        generation,request=self.generation,self.track_generation
        self.tabs.setCurrentIndex(0);self.status.setText('Cargando '+album['title']+'…');self.back.show()
        self.window.jobs.submit(lambda cancel,progress:self.window.catalog.album_tracks(album),
                                lambda tracks:self.tracks_ready(tracks,generation,request),
                                lambda e:self.error(e) if generation==self.generation and request==self.track_generation and not self.closed else None)

    def load_track_covers(self):
        if self.closed:return
        for row in self.table.visible_rows():
            if row>=len(self.tracks):continue
            source=self.tracks[row].cover
            if source and source not in self.model.covers:
                self.window.load_cover(source,lambda path,key=source:self.model.set_cover(key,path) if not self.closed else None)

    def load_album_covers(self, *_):
        if self.closed or self.tabs.currentIndex()!=1:return
        viewport=self.albums.viewport().rect()
        for row in range(self.albums.count()):
            item=self.albums.item(row)
            if not self.albums.visualItemRect(item).intersects(viewport) or not item.icon().isNull():continue
            source=item.data(Qt.ItemDataRole.UserRole)['cover']
            generation=self.generation
            def ready(path, item=item, generation=generation):
                if not self.closed and generation==self.generation and path:item.setIcon(QIcon(path))
            self.window.load_cover(source,ready)
