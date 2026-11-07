from PyQt6.QtGui import QImage, QPainter
from PyQt6.QtCore import Qt
from musicvault.models import Track
from musicvault.widgets import icon, CircularPlayButton


def test_remote_favorites_are_visible_immediately(app, db):
    from musicvault.window import Window
    from musicvault.player import Player
    db.set_setting('taste_onboarding_complete', True)
    db.set_setting('auto_scan', False)
    player=Player(db,audio_output='null')
    window=Window(db,player)
    track=Track('remote:fav','Remote favorite','An artist',source='youtube',source_id='abcdefghijk')
    try:
        window.show_route('Favoritos')
        window.favorite(track)
        assert track.id in [t.id for t in window.shown_tracks]
        assert 'An artist' in db.library_facets(favorites=True)[0]
        assert db.library_tracks()==[]
        window.favorite(track)
        assert track.id not in [t.id for t in window.shown_tracks]
    finally:
        window.quit_app()


def test_liked_heart_center_is_solid_red(app):
    filled=icon('heart_filled','#E53935',24).pixmap(48,48).toImage()
    outline=icon('heart','#E53935',24).pixmap(48,48).toImage()
    assert filled.pixelColor(24,24).red()>200
    assert filled.pixelColor(24,24).alpha()==255
    assert outline.pixelColor(24,24).alpha()==0


def test_play_button_draws_a_circle_for_both_sizes(app):
    for size in (42,64):
        button=CircularPlayButton()
        button.setFixedSize(size,size)
        image=QImage(size,size,QImage.Format.Format_ARGB32)
        image.fill(Qt.GlobalColor.transparent)
        painter=QPainter(image)
        button.render(painter);painter.end()
        # Compare opposite edges of the actual painted disc, independent of the
        # opaque background QWidget.render may draw behind it.
        center=image.pixelColor(size//2,size//2)
        assert image.pixelColor(size//2,1)==center
        assert image.pixelColor(1,size//2)==center
        assert image.pixelColor(0,0)!=center
