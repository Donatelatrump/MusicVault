from PyQt6.QtCore import Qt, QPoint
from PyQt6.QtTest import QTest


def make_window(db):
    from musicvault.player import Player
    from musicvault.window import Window
    db.set_setting('taste_onboarding_complete',True);db.set_setting('auto_scan',False)
    return Window(db,Player(db,audio_output='null'))


def test_lobby_matches_room_design_without_exposing_raw_uid(app,db,monkeypatch):
    window=make_window(db);shared=window.shared_jams
    sent=[];monkeypatch.setattr(shared,'start',lambda:None)
    monkeypatch.setattr(shared,'send',lambda action,**kw:sent.append((action,kw)))
    shared.connected=True;shared.identity={'uid':'self','installationUid':'private-installation-id'}
    try:
        shared.show();QTest.qWait(50);dialog=shared.dialog
        assert dialog.lobby.isVisible()
        assert dialog.room_panel.isHidden()
        assert dialog.code.isHidden()
        assert dialog.stage.height()==266
        assert not any('private-installation-id' in label.text() for label in dialog.findChildren(__import__('PyQt6.QtWidgets',fromlist=['QLabel']).QLabel))
        dialog.create.click();assert sent==[('create',{})]
    finally:window.quit_app()


def test_active_room_queue_controls_favorite_and_code(app,db,monkeypatch):
    window=make_window(db);shared=window.shared_jams
    shared.timer.stop();shared.connected=True;shared.identity={'uid':'self'}
    monkeypatch.setattr(shared,'start',lambda:None)
    sent=[];monkeypatch.setattr(shared,'send',lambda action,**kw:sent.append((action,kw)))
    monkeypatch.setattr(window,'load_cover',lambda source,callback:None)
    shared.room={'code':'a'*32,'value':{'meta':{'shortCode':'123456','hostUid':'self'},
                'members':{'self':True,'guest1':True},'queue':{'one':{'videoId':'abcdefghijk','title':'Song','artist':'Artist','durationMs':100000}},
                'playback':{'songKey':'one','playing':False,'positionMs':0,'revision':1}}}
    try:
        shared.show();QTest.qWait(50);dialog=shared.dialog
        assert dialog.lobby.isHidden()
        assert dialog.room_panel.isVisible()
        assert dialog.code.text()=='123 456'
        assert dialog.queue.count()==1
        song=dialog.queue.item(0).data(Qt.ItemDataRole.UserRole)
        dialog.queue_action('favorite',song)
        assert 'youtube:jam:abcdefghijk' in db.favorites()
        dialog.queue_action('play',song)
        assert sent[-1][0]=='playback' and sent[-1][1]['value']['songKey']=='one'
        dialog.play.click()
        assert sent[-1][1]['value']['playing'] is True
        dialog.copy_code();assert __import__('PyQt6.QtWidgets',fromlist=['QApplication']).QApplication.clipboard().text()=='123456'
        dialog.queue.setCurrentRow(0)
        QTest.mouseClick(dialog.queue.viewport(),Qt.MouseButton.LeftButton,pos=QPoint(dialog.queue.viewport().width()-18,35))
        assert sent[-1]==('removeSong',{'key':'one'})
    finally:
        shared.room=None;window.quit_app()
