"""Desktop adaptation of Android JamsScreen: room stage, music and private friends."""
import hashlib
import math

from PyQt6.QtCore import Qt, QRectF, QSize, QTimer, pyqtSignal
from PyQt6.QtGui import QColor, QPainter, QPainterPath, QPen, QIcon, QPixmap
from PyQt6.QtWidgets import (QApplication, QDialog, QWidget, QFrame, QVBoxLayout,
                            QHBoxLayout, QLabel, QPushButton, QScrollArea, QListWidget,
                            QListWidgetItem, QLineEdit, QInputDialog, QMenu, QMessageBox,
                            QAbstractItemView, QStyledItemDelegate, QStyle)
from .models import Track
from .widgets import icon, CircularPlayButton

ACCENT = '#E0A458'
MUTED = '#B3A992'
STYLE = '''
QDialog#jamsWindow {background:#100F0D;}
QFrame#jamCard {background:#1C1915;border:1px solid #332D24;border-radius:20px;}
QLabel#jamHeading {font-size:28px;font-weight:700;color:#F1EAD9;}
QLabel#jamSection {font-size:18px;font-weight:600;color:#F1EAD9;}
QLabel#jamMuted {color:#B3A992;}
QPushButton#jamPrimary {background:#E0A458;color:#191207;border:0;border-radius:14px;min-height:46px;padding:0 22px;font-weight:600;}
QPushButton#jamPrimary:hover {background:#E9B66F;}
QPushButton#jamPrimary:disabled {background:#4B3B27;color:#9B8C76;}
QPushButton#jamLink {background:transparent;border:0;color:#E0A458;padding:10px;}
QPushButton#jamCode {background:#30271B;border:1px solid #594328;border-radius:12px;color:#E0A458;padding:10px 16px;font-size:17px;font-weight:700;}
QListWidget#jamQueue {background:transparent;border:0;outline:0;}
QListWidget#jamQueue::item {background:#1C1915;border:1px solid #332D24;border-radius:12px;padding:10px;margin-bottom:6px;}
QListWidget#jamQueue::item:selected {background:#382C1C;border-color:#6B5030;}
QScrollArea#jamScroll {background:transparent;border:0;}
'''


def label(text='', name='jamMuted'):
    item=QLabel(text);item.setTextFormat(Qt.TextFormat.PlainText);item.setObjectName(name)
    return item


def button(text, callback, name='jamLink'):
    item=QPushButton(text);item.setObjectName(name);item.setCursor(Qt.CursorShape.PointingHandCursor)
    item.clicked.connect(callback);return item


def guest(uid):
    return 'Invitado '+uid[-4:].upper()


def avatar(painter, rect, uid, own=False, online=False):
    tones=['#E0A458','#A3B594','#BE8991','#8FAEBB','#AD99C6','#C29E7A']
    tone=QColor(tones[int(hashlib.sha256(uid.encode()).hexdigest()[:8],16)%len(tones)])
    painter.setPen(QPen(QColor(ACCENT if own else '#443A2E'),2 if own else 1))
    painter.setBrush(QColor('#29241D'));painter.drawEllipse(rect)
    painter.save();clip=QPainterPath();clip.addEllipse(rect.adjusted(3,3,-3,-3));painter.setClipPath(clip)
    painter.setPen(Qt.PenStyle.NoPen);painter.setBrush(tone)
    w=rect.width();x=rect.left();y=rect.top()
    painter.drawEllipse(QRectF(x+w*.32,y+w*.19,w*.36,w*.36))
    painter.drawEllipse(QRectF(x+w*.19,y+w*.59,w*.62,w*.56))
    painter.setBrush(QColor('#1C1915'))
    for eye in (.43,.55):painter.drawEllipse(QRectF(x+w*eye,y+w*.35,w*.035,w*.035))
    painter.restore()
    if online:
        painter.setPen(QPen(QColor('#1C1915'),2));painter.setBrush(QColor('#9AB88B'))
        painter.drawEllipse(QRectF(rect.right()-10,rect.bottom()-10,11,11))


class RoomStage(QWidget):
    invite = pyqtSignal()
    person = pyqtSignal(str)

    def __init__(self, shared, parent=None):
        super().__init__(parent);self.shared=shared;self.hits=[];self.cover=QPixmap()
        self.setFixedHeight(266);self.setMinimumWidth(360)
        self.setCursor(Qt.CursorShape.PointingHandCursor)

    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing)
        w=self.width();p.setPen(QPen(QColor('#332D24'),1));p.setBrush(QColor('#1C1915'))
        p.drawRoundedRect(QRectF(.5,.5,w-1,self.height()-1),24,24)
        p.setPen(QPen(QColor('#3D352A'),1));p.setBrush(QColor('#25211B'))
        p.drawEllipse(QRectF(w*.065,60,w*.87,144))
        p.setPen(QPen(QColor('#332D24'),1))
        for y in (90,121,152):p.drawLine(int(w*.15),y,int(w*.85),y)
        center=QRectF(w/2-47,82,94,94)
        p.setPen(QPen(QColor('#6B5030'),1));p.setBrush(QColor('#30271B'));p.drawEllipse(center)
        disc=center.adjusted(9,9,-9,-9)
        p.save();clip=QPainterPath();clip.addEllipse(disc);p.setClipPath(clip)
        if not self.cover.isNull():
            size=min(self.cover.width(),self.cover.height())
            p.drawPixmap(disc,self.cover,QRectF((self.cover.width()-size)/2,(self.cover.height()-size)/2,size,size))
        else:
            p.fillRect(disc,QColor('#100F0D'));icon('album',ACCENT,42).paint(p,disc.adjusted(17,17,-17,-17).toRect())
        p.restore();p.setPen(QPen(QColor(ACCENT),1));p.setBrush(QColor('#1C1915'));p.drawEllipse(QRectF(w/2-5,124,10,10))
        shared=self.shared;own=shared.identity.get('uid') or shared.identity.get('installationUid') or 'self'
        data=shared.room['value'] if shared.active else {}
        people=[own]+[uid for uid,online in data.get('members',{}).items() if online and uid!=own]
        visible=people[:8];seats=max(4,len(visible));self.hits=[]
        p.setPen(QColor(MUTED));p.drawText(20,28,f'{len(people)} '+('participante' if len(people)==1 else 'participantes'))
        if len(people)>8:p.drawText(w-50,28,'+'+str(len(people)-8))
        for i in range(seats):
            angle=math.radians(90+i*360/seats)
            x=w/2+w*.35*math.cos(angle);y=129+80*math.sin(angle)
            rect=QRectF(x-27,y-27,54,54)
            uid=visible[i] if i<len(visible) else None
            if uid:
                avatar(p,rect,uid,uid==own,shared.connected and shared.active)
                p.setPen(QColor(ACCENT if uid==own else MUTED))
                text='Tú' if uid==own else guest(uid)
                p.drawText(QRectF(x-60,y+31,120,18),Qt.AlignmentFlag.AlignCenter,text)
            else:
                p.setPen(QPen(QColor('#554837'),1,Qt.PenStyle.DashLine));p.setBrush(Qt.BrushStyle.NoBrush);p.drawEllipse(rect)
                p.setPen(QPen(QColor(MUTED),1.5));p.drawLine(int(x-7),int(y),int(x+7),int(y));p.drawLine(int(x),int(y-7),int(x),int(y+7))
            self.hits.append((rect,uid))
        p.end()

    def mouseReleaseEvent(self,event):
        if event.button()!=Qt.MouseButton.LeftButton:return
        own=self.shared.identity.get('uid') or self.shared.identity.get('installationUid') or 'self'
        for rect,uid in self.hits:
            if rect.contains(event.position()):
                if uid is None:self.invite.emit()
                elif uid!=own:self.person.emit(uid)
                break


class QueueDelegate(QStyledItemDelegate):
    def paint(self,painter,option,index):
        song=index.data(Qt.ItemDataRole.UserRole)
        if not song:return
        painter.save();painter.setRenderHint(QPainter.RenderHint.Antialiasing)
        active=index.data(int(Qt.ItemDataRole.UserRole)+2)
        rect=QRectF(option.rect).adjusted(0,2,-1,-4)
        painter.setPen(QPen(QColor('#6B5030' if active else '#332D24'),1))
        painter.setBrush(QColor('#382C1C' if active or option.state & QStyle.StateFlag.State_Selected else '#1C1915'))
        painter.drawRoundedRect(rect,12,12)
        art=index.data(Qt.ItemDataRole.DecorationRole)
        art_rect=QRectF(rect.left()+12,rect.top()+10,48,48)
        if art and not art.isNull():art.paint(painter,art_rect.toRect())
        else:icon('album',MUTED,32).paint(painter,art_rect.adjusted(8,8,-8,-8).toRect())
        text=QRectF(rect.left()+72,rect.top()+12,max(20,rect.width()-186),22)
        painter.setFont(option.font);painter.setPen(QColor(ACCENT if active else '#F1EAD9'))
        painter.drawText(text,Qt.AlignmentFlag.AlignVCenter,painter.fontMetrics().elidedText(song['title'],Qt.TextElideMode.ElideRight,int(text.width())))
        painter.setPen(QColor(MUTED));text.translate(0,23)
        painter.drawText(text,Qt.AlignmentFlag.AlignVCenter,painter.fontMetrics().elidedText(song['artist'],Qt.TextElideMode.ElideRight,int(text.width())))
        liked=index.data(int(Qt.ItemDataRole.UserRole)+1)
        x=option.rect.right()-30;y=option.rect.center().y()-10
        for name,color,offset in [('heart_filled' if liked else 'heart','#E53935' if liked else MUTED,64),('plus',MUTED,32),('close',MUTED,0)]:
            icon(name,color,20).paint(painter, int(x-offset),y,20,20)
        painter.restore()


class JamQueue(QListWidget):
    action = pyqtSignal(str,object)

    def mouseReleaseEvent(self,event):
        super().mouseReleaseEvent(event)
        item=self.itemAt(event.position().toPoint())
        if not item or event.button()!=Qt.MouseButton.LeftButton:return
        right=self.visualItemRect(item).right()-event.position().x()
        action='remove' if right<42 else 'collection' if right<74 else 'favorite' if right<106 else 'play'
        self.action.emit(action,item.data(Qt.ItemDataRole.UserRole))


class FriendsStrip(QWidget):
    person=pyqtSignal(str)
    add=pyqtSignal()

    def __init__(self,shared):
        super().__init__();self.shared=shared;self.setFixedHeight(94);self.hits=[]
        self.setCursor(Qt.CursorShape.PointingHandCursor)

    def paintEvent(self,event):
        p=QPainter(self);p.setRenderHint(QPainter.RenderHint.Antialiasing);self.hits=[]
        people=[None]+list(self.shared.contacts.get('friends',{}))
        for i,uid in enumerate(people[:max(1,self.width()//94)]):
            x=20+i*94;rect=QRectF(x,3,54,54)
            if uid:avatar(p,rect,uid)
            else:
                p.setPen(QPen(QColor('#554837'),1));p.setBrush(Qt.BrushStyle.NoBrush);p.drawEllipse(rect)
                icon('plus',ACCENT,24).paint(p,rect.adjusted(15,15,-15,-15).toRect())
            p.setPen(QColor(MUTED));p.drawText(QRectF(x-17,64,88,20),Qt.AlignmentFlag.AlignCenter,guest(uid) if uid else 'Agregar')
            self.hits.append((rect,uid))

    def mouseReleaseEvent(self,event):
        if event.button()!=Qt.MouseButton.LeftButton:return
        for rect,uid in self.hits:
            if rect.contains(event.position()):
                if uid:self.person.emit(uid)
                else:self.add.emit()
                return


class JamsDialog(QDialog):
    def __init__(self,shared):
        super().__init__(shared.window)
        self.shared=shared;self.window=shared.window;self.queue_signature=None;self.cover_source=None;self.queue_generation=0
        self.setObjectName('jamsWindow');self.setWindowTitle('MusicVault · JAMS')
        self.resize(900,820);self.setMinimumSize(720,620);self.setStyleSheet(STYLE)
        root=QVBoxLayout(self);root.setContentsMargins(24,20,24,20);root.setSpacing(16)
        header=QHBoxLayout();heading=QVBoxLayout();heading.addWidget(label('JAMS','jamHeading'))
        self.connection=label();heading.addWidget(self.connection);header.addLayout(heading,1)
        self.people_button=button('Amigos',self.show_people);self.people_button.setIcon(icon('user',MUTED,20));header.addWidget(self.people_button)
        self.reconnect=button('Reintentar conexión',shared.start);header.addWidget(self.reconnect)
        self.code=button('',self.copy_code,'jamCode');header.addWidget(self.code)
        self.options=button('•••',self.show_options);self.options.setAccessibleName('Opciones de la sala');header.addWidget(self.options)
        root.addLayout(header)
        scroll=QScrollArea();scroll.setObjectName('jamScroll');scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        surface=QWidget();self.body=QVBoxLayout(surface);self.body.setContentsMargins(0,0,8,0);self.body.setSpacing(18)
        self.stage=RoomStage(shared);self.stage.invite.connect(self.invite);self.stage.person.connect(self.person_menu);self.body.addWidget(self.stage)
        self.lobby=QWidget();lobby=QVBoxLayout(self.lobby);lobby.setContentsMargins(0,2,0,0);lobby.setSpacing(12)
        lobby.addWidget(label('La música suena mejor en compañía','jamSection'))
        desc=label('Crea una sala, invita a tus amigos y escuchen juntos.');desc.setWordWrap(True);lobby.addWidget(desc)
        self.create=button('＋  Crear JAMS',lambda:shared.send('create'),'jamPrimary');lobby.addWidget(self.create)
        self.join=button('Unirme con un código o enlace',self.join_room);lobby.addWidget(self.join);self.body.addWidget(self.lobby)
        self.room_panel=QWidget();room=QVBoxLayout(self.room_panel);room.setContentsMargins(0,0,0,0);room.setSpacing(16)
        room.addWidget(button('Invitar a la sala',self.invite,'jamPrimary'))
        now=QFrame();now.setObjectName('jamCard');nowbox=QVBoxLayout(now);nowbox.setContentsMargins(20,16,20,16)
        self.song_title=label('Listos para escuchar','jamSection');self.song_title.setAlignment(Qt.AlignmentFlag.AlignCenter);self.song_title.setWordWrap(True);nowbox.addWidget(self.song_title)
        self.song_artist=label();self.song_artist.setAlignment(Qt.AlignmentFlag.AlignCenter);nowbox.addWidget(self.song_artist)
        controls=QHBoxLayout();controls.addStretch()
        self.previous=button('',lambda:shared.transport('previous'));self.previous.setIcon(icon('previous',MUTED,24));self.previous.setAccessibleName('Anterior');controls.addWidget(self.previous)
        self.play=CircularPlayButton();self.play.setObjectName('playCircle');self.play.setFixedSize(56,56);self.play.setIconSize(QSize(28,28));self.play.clicked.connect(lambda:shared.transport('toggle'));self.play.setAccessibleName('Reproducir o pausar');controls.addWidget(self.play)
        self.next=button('',lambda:shared.transport('next'));self.next.setIcon(icon('next',MUTED,24));self.next.setAccessibleName('Siguiente');controls.addWidget(self.next)
        self.retry=button('Reintentar audio',shared.retry_audio);controls.addWidget(self.retry);controls.addStretch();nowbox.addLayout(controls);room.addWidget(now)
        queue_header=QHBoxLayout();self.queue_heading=label('Cola compartida','jamSection');queue_header.addWidget(self.queue_heading,1)
        queue_header.addWidget(button('＋  Añadir música',self.show_songs));room.addLayout(queue_header)
        self.empty_queue=button('＋  Elige la primera canción',self.show_songs);self.empty_queue.setMinimumHeight(72);room.addWidget(self.empty_queue)
        self.queue=JamQueue();self.queue.setObjectName('jamQueue');self.queue.setIconSize(QSize(48,48));self.queue.setUniformItemSizes(True)
        self.queue.setItemDelegate(QueueDelegate(self.queue));self.queue.setTextElideMode(Qt.TextElideMode.ElideRight)
        self.queue.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        self.queue.action.connect(self.queue_action);self.queue.setContextMenuPolicy(Qt.ContextMenuPolicy.CustomContextMenu);self.queue.customContextMenuRequested.connect(self.queue_menu)
        self.queue.verticalScrollBar().valueChanged.connect(self.load_queue_covers)
        room.addWidget(self.queue);self.body.addWidget(self.room_panel)
        friends_header=QHBoxLayout();friends_header.addWidget(label('Amigos','jamSection'),1);friends_header.addWidget(button('Ver todos',self.show_people));self.body.addLayout(friends_header)
        self.friend_strip=FriendsStrip(shared);self.friend_strip.person.connect(self.person_menu);self.friend_strip.add.connect(self.add_friend);self.body.addWidget(self.friend_strip)
        self.friend_summary=label();self.friend_summary.setWordWrap(True);self.body.addWidget(self.friend_summary)
        self.body.addStretch()
        scroll.setWidget(surface);root.addWidget(scroll,1)
        self.message=label();self.message.setWordWrap(True);root.addWidget(self.message)
        self.people_dialog=None;self.people_signature=None
        shared.updated.connect(self.refresh);self.refresh()

    def refresh(self):
        shared=self.shared;active=shared.active;data=shared.room['value'] if active else {}
        self.connection.setText('● Sala en vivo' if active and shared.connected else 'Conectando…' if not shared.connected else 'Escucha con tus amigos')
        self.connection.setStyleSheet('color:#9AB88B;' if active and shared.connected else 'color:#B3A992;')
        self.lobby.setVisible(not active);self.room_panel.setVisible(active);self.code.setVisible(active);self.options.setVisible(active)
        code=data.get('meta',{}).get('shortCode','');self.code.setText(code[:3]+' '+code[3:]);self.code.setToolTip('Copiar código de la sala')
        self.create.setEnabled(shared.connected);self.join.setEnabled(shared.connected)
        self.reconnect.setVisible(not shared.connected)
        pending=len(shared.contacts.get('friendRequests',{}))+len(shared.contacts.get('roomInvites',{}))
        self.people_button.setText('Amigos'+(f' · {pending}' if pending else ''))
        friends=list(shared.contacts.get('friends',{}));self.friend_summary.setText('   ·   '.join(guest(uid) for uid in friends[:6]) if friends else 'Tu próxima escucha compartida empieza con una invitación.')
        self.friend_summary.setVisible(not friends);self.friend_strip.update()
        p=data.get('playback',{});songs=data.get('queue',{});current=songs.get(p.get('songKey'),{})
        self.song_title.setText(current.get('title','Listos para escuchar'));self.song_artist.setText(current.get('artist','Añade música para empezar'))
        self.play.setIcon(icon('pause' if p.get('playing') else 'play','#191207',28))
        for control in (self.previous,self.play,self.next):control.setEnabled(shared.connected and bool(songs))
        self.retry.setVisible(bool(shared.failed_key))
        self.queue_heading.setText(f'Cola compartida · {len(songs)}');self.empty_queue.setVisible(not songs);self.queue.setVisible(bool(songs))
        self.queue.setEnabled(shared.connected)
        favorites=self.window.db.favorites()
        signature=(repr(sorted(songs.items())),p.get('songKey'),tuple(sorted(favorites)))
        if signature!=self.queue_signature:
            self.queue_generation+=1
            self.queue_signature=signature;selected=self.queue.currentItem();selected=selected.data(Qt.ItemDataRole.UserRole)['key'] if selected else None
            scroll=self.queue.verticalScrollBar().value();self.queue.clear()
            for key,song in sorted(songs.items()):
                entry=dict(song,key=key);item=QListWidgetItem(song['title']+'\n'+song['artist']+'                     ')
                item.setData(Qt.ItemDataRole.UserRole,entry);item.setData(int(Qt.ItemDataRole.UserRole)+1,'youtube:jam:'+song['videoId'] in favorites)
                item.setData(int(Qt.ItemDataRole.UserRole)+2,key==p.get('songKey'))
                item.setToolTip(song['title']+' — '+song['artist']);item.setSizeHint(QSize(200,76));self.queue.addItem(item)
                if key==p.get('songKey'):item.setForeground(QColor(ACCENT))
                if key==selected:item.setSelected(True)
            self.queue.setFixedHeight(min(5,max(1,len(songs)))*82+4);self.queue.verticalScrollBar().setValue(scroll)
            QTimer.singleShot(0,self.load_queue_covers)
        source=current.get('videoId')
        if source!=self.cover_source:
            self.cover_source=source;self.stage.cover=QPixmap()
            if source:
                def ready(path,source=source):
                    if source==self.cover_source:self.stage.cover=QPixmap(path);self.stage.update()
                self.window.load_cover('https://i.ytimg.com/vi/'+source+'/hqdefault.jpg',ready)
        self.stage.update()
        routine={'Conectado','Sala conectada','Sin sala','Conecta para usar JAMS y Amigos.'}
        self.message.setText('' if shared.message in routine else shared.message);self.message.setVisible(bool(self.message.text()))
        self.refresh_people()

    def load_queue_covers(self,*_):
        first=max(0,self.queue.indexAt(self.queue.viewport().rect().topLeft()).row())
        generation=self.queue_generation
        for row in range(first,min(first+7,self.queue.count())):
            item=self.queue.item(row)
            if not item.icon().isNull():continue
            video=item.data(Qt.ItemDataRole.UserRole)['videoId']
            def ready(path,item=item,generation=generation):
                if self.queue_generation==generation and path:item.setIcon(QIcon(path))
            self.window.load_cover('https://i.ytimg.com/vi/'+video+'/hqdefault.jpg',ready)

    def join_room(self):
        value,ok=QInputDialog.getText(self,'Unirme a JAMS','Código de seis dígitos o enlace de invitación:')
        if ok and value.strip():self.shared.send('join',value=value.strip())

    def copy_code(self):
        if self.shared.active:
            QApplication.clipboard().setText(self.shared.room['value']['meta']['shortCode']);self.shared.error('Código de sala copiado.')

    def invite(self):
        if not self.shared.active:self.shared.send('create');return
        QApplication.clipboard().setText('https://musicvault-jams-donatelatrump.web.app/jam/'+self.shared.room['code']);self.shared.error('Invitación copiada. Compártela con tus amigos.')

    def show_options(self):
        menu=QMenu(self)
        menu.addAction('Copiar invitación',self.invite);menu.addAction('Copiar código',self.copy_code)
        menu.addAction('Información de mi instalación',lambda:QMessageBox.information(self,'Tu instalación',self.shared.identity.get('installationUid','Conectando…')))
        menu.addSeparator();menu.addAction('Salir de la sala',lambda:self.leave(False))
        if self.shared.active and self.shared.room['value']['meta']['hostUid']==self.shared.identity.get('uid'):
            menu.addAction('Cerrar sala para todos',lambda:self.leave(True))
        menu.exec(self.options.mapToGlobal(self.options.rect().bottomLeft()))

    def leave(self,close):
        text='¿Cerrar esta sala para todos?' if close else '¿Salir de esta sala?'
        if QMessageBox.question(self,'JAMS',text)==QMessageBox.StandardButton.Yes:self.shared.send('close' if close else 'leave')

    def song_track(self,song):
        video=song['videoId'];return Track('youtube:jam:'+video,song['title'],song['artist'],'JAMS',song.get('durationMs',0)/1000,source='youtube',source_id=video,url='https://www.youtube.com/watch?v='+video)

    def queue_action(self,action,song):
        if action=='remove':self.shared.send('removeSong',key=song['key'])
        elif action=='collection':self.window.add_to_collection([self.song_track(song)])
        elif action=='favorite':self.window.favorite(self.song_track(song));self.refresh()
        else:self.shared.command(songKey=song['key'],playing=True,positionMs=0,buffering=True,action='select')

    def queue_menu(self,point):
        item=self.queue.itemAt(point)
        if not item:return
        song=item.data(Qt.ItemDataRole.UserRole);track=self.song_track(song);menu=QMenu(self)
        menu.addAction('Reproducir para todos',lambda:self.shared.command(songKey=song['key'],playing=True,positionMs=0,buffering=True,action='select'))
        menu.addAction('Me gusta / Quitar Me gusta',lambda:(self.window.favorite(track),self.refresh()))
        menu.addAction('Añadir a una colección',lambda:self.window.add_to_collection([track]))
        menu.addAction('Quitar de la cola',lambda:self.shared.send('removeSong',key=song['key']));menu.exec(self.queue.viewport().mapToGlobal(point))

    def add_friend(self):
        menu=QMenu(self);menu.addAction('Copiar mi enlace de amistad',lambda:self.shared.send('friendLink'))
        def request():
            value,ok=QInputDialog.getText(self,'Agregar amigo','Pega su enlace de amistad:')
            if ok and value.strip():self.shared.send('request',value=value.strip())
        menu.addAction('Agregar con un enlace',request);menu.exec(self.mapToGlobal(self.rect().center()))

    def person_menu(self,uid):
        if uid==self.shared.identity.get('uid'):return
        menu=QMenu(self);menu.addSection(guest(uid))
        if uid in self.shared.contacts.get('friends',{}):
            if self.shared.active:menu.addAction('Invitar a la sala',lambda:self.shared.send('invite',uid=uid))
            menu.addAction('Eliminar amigo',lambda:self.shared.send('removeFriend',uid=uid))
        elif self.shared.active:menu.addAction('Enviar solicitud de amistad',lambda:self.shared.send('requestMember',uid=uid))
        menu.exec(self.mapToGlobal(self.rect().center()))

    def show_people(self):
        if not self.people_dialog:
            dialog=self.people_dialog=QDialog(self);dialog.setWindowTitle('Amigos e invitaciones');dialog.resize(520,520)
            layout=QVBoxLayout(dialog);layout.addWidget(label('Amigos','jamHeading'))
            layout.addWidget(button('＋ Agregar amigo',self.add_friend,'jamPrimary'))
            self.people_list=QListWidget();self.people_list.setSpacing(6);layout.addWidget(self.people_list,1)
            self.people_list.itemClicked.connect(self.contact_action)
            layout.addWidget(label('Selecciona una persona o invitación para ver sus acciones.'))
        self.people_signature=None;self.refresh_people();self.people_dialog.show();self.people_dialog.raise_()

    def refresh_people(self):
        if not self.people_dialog:return
        signature=repr(self.shared.contacts)
        if signature==self.people_signature:return
        self.people_signature=signature;self.people_list.clear()
        for group,title in [('roomInvites','Te invita a una sala'),('friendRequests','Solicitud de amistad'),('friends','Amigo')]:
            for uid,value in self.shared.contacts.get(group,{}).items():
                item=QListWidgetItem(guest(uid)+'\n'+title);item.setSizeHint(QSize(200,64))
                face=QPixmap(60,60);face.fill(Qt.GlobalColor.transparent);painter=QPainter(face);painter.setRenderHint(QPainter.RenderHint.Antialiasing);avatar(painter,QRectF(3,3,54,54),uid);painter.end()
                item.setIcon(QIcon(face));self.people_list.setIconSize(QSize(48,48));item.setData(Qt.ItemDataRole.UserRole,(group,uid,value));self.people_list.addItem(item)
        if not self.people_list.count():
            item=QListWidgetItem('Todavía no hay amigos ni invitaciones.');item.setFlags(Qt.ItemFlag.NoItemFlags);self.people_list.addItem(item)

    def contact_action(self,item):
        value=item.data(Qt.ItemDataRole.UserRole)
        if not value:return
        group,uid,data=value;menu=QMenu(self.people_dialog)
        if group=='friends':self.person_menu(uid);return
        if group=='friendRequests':
            menu.addAction('Aceptar solicitud',lambda:self.shared.send('accept',uid=uid));menu.addAction('Rechazar solicitud',lambda:self.shared.send('reject',uid=uid))
        else:
            def join():
                if self.shared.active:
                    self.shared.error('Sal de tu sala actual antes de aceptar otra invitación.');return
                self.shared.send('join',value=data['roomCode']);self.shared.send('dismiss',uid=uid);self.people_dialog.hide()
            menu.addAction('Unirme a la sala',join);menu.addAction('Rechazar invitación',lambda:self.shared.send('dismiss',uid=uid))
        menu.exec(self.people_list.mapToGlobal(self.people_list.rect().center()))

    def show_songs(self):
        picker=QDialog(self);picker.setWindowTitle('Añadir música a JAMS');picker.resize(660,600)
        layout=QVBoxLayout(picker);layout.addWidget(label('Añadir música','jamHeading'))
        search=QLineEdit();search.setPlaceholderText('Buscar una canción o artista');layout.addWidget(search)
        status=label('Selecciona música de tu biblioteca o busca en línea.');layout.addWidget(status)
        results=QListWidget();results.setIconSize(QSize(44,44));layout.addWidget(results,1)
        request=[0];closed=[False]
        def fill(tracks):
            results.clear()
            for track in tracks:
                item=QListWidgetItem(track.title+'\n'+track.artist);item.setData(Qt.ItemDataRole.UserRole,track);item.setSizeHint(QSize(200,64));results.addItem(item)
        current=self.window.player.current
        tracks=self.window.db.library_tracks()[:50]
        if current and current.id not in {t.id for t in tracks}:tracks.insert(0,current)
        fill(tracks)
        def run():
            query=search.text().strip();request[0]+=1;token=request[0]
            if len(query)<2:fill(tracks);return
            status.setText('Buscando música…')
            from .streaming import youtube_search
            def ready(values):
                if not closed[0] and token==request[0]:fill(values);status.setText('' if values else 'No se encontraron canciones.')
            def failed(error):
                if not closed[0] and token==request[0]:status.setText('No se pudo buscar. Reintenta con conexión.')
            self.window.jobs.submit(lambda cancel,progress:youtube_search(query,cancel,20),ready,failed)
        timer=QTimer(picker);timer.setSingleShot(True);timer.setInterval(350);timer.timeout.connect(run)
        search.textChanged.connect(lambda _:(request.__setitem__(0,request[0]+1),timer.start()));search.returnPressed.connect(lambda:(timer.stop(),run()))
        def add(play=False):
            item=results.currentItem()
            if item and self.shared.active:
                self.shared.add(item.data(Qt.ItemDataRole.UserRole),play);status.setText('Preparando la canción para la sala…')
        actions=QHBoxLayout();actions.addWidget(button('Añadir a la cola',lambda:add(False),'jamPrimary'));actions.addWidget(button('Reproducir para todos',lambda:add(True)));layout.addLayout(actions)
        results.itemDoubleClicked.connect(lambda _:add(True))
        picker.finished.connect(lambda _:closed.__setitem__(0,True));picker.show()
