from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import sqlite3

from musicvault.database import Database
from musicvault.library import read_track, scan
from musicvault.models import Track, parse_lrc


def test_metadata_import_unicode_and_duplicate_paths(db, audio_file):
    path = audio_file("Música con espacios/canción.wav", 2.5)
    tracks, errors = scan([path, path.parent], db, explicit=True)
    assert not errors
    assert len(tracks) == len(db.tracks()) == 1
    assert tracks[0].title == "canción"
    assert tracks[0].duration == 2.5
    assert path.exists()


def test_removed_download_not_resurrected_by_scan(db, audio_file):
    path = audio_file()
    downloaded = replace(read_track(path), id="deezer:123", source="deezer")
    db.save_track(downloaded)
    db.remove_track(downloaded.id)
    scan([path.parent], db)
    assert db.tracks() == []
    scan([path], db, explicit=True)
    assert [t.id for t in db.tracks()] == [downloaded.id]


def test_collection_order_favorites_persistence_and_backup(db, audio_file, tmp_path):
    tracks = [read_track(audio_file(f"{i}.wav")) for i in range(3)]
    key = db.create_collection("Mis favoritos", "album")
    for track in tracks:
        db.add_member(key, track)
        db.add_member(key, track)
    ids = [t.id for t in reversed(tracks)]
    db.reorder_members(key, ids)
    assert [t.id for t in db.tracks(collection=key)] == ids
    db.toggle_favorite(tracks[0])
    db.set_setting("crossfade", 6)
    reopened = Database(db.path)
    assert reopened.favorites() == {tracks[0].id}
    assert reopened.setting("crossfade") == 6
    backup = tmp_path / "backup.sqlite3"
    db.export_backup(backup)
    with sqlite3.connect(backup) as connection:
        assert connection.execute("SELECT COUNT(*) FROM members").fetchone()[0] == 3
    db.delete_collection(key)
    assert len(db.tracks()) == 3


def test_concurrent_writes_and_preserved_download_path(db):
    track = Track("id", "Title", path="/music/file.mp3")
    db.save_track(track)
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(lambda i: db.cache_put(str(i), {"value": i}), range(40)))
    db.toggle_favorite(replace(track, path=""))
    assert db.get_track("id").path == "/music/file.mp3"
    assert db.cache_get("39") == {"value": 39}
    assert db.cache_get("39", ttl=-1) is None


def test_lrc_multiple_stamps_fraction_offset():
    result = parse_lrc("[offset:-100]\n[00:01.2][00:03.045]Hola\n[01:02]Final\n[ar:Artist]")
    assert result == [(1.0999999999999999, "Hola"), (2.945, "Hola"), (61.9, "Final")]


def test_filters_corrupt_files_and_search(db, audio_file, tmp_path):
    path = audio_file("Una canción.wav", 3)
    (tmp_path / "broken.mp3").write_bytes(b"invalid")
    tracks, errors = scan([tmp_path], db, minimum=2)
    assert len(tracks) == 1 and len(errors) == 1
    assert db.tracks("CANCIÓN")[0].path == str(path)
    history = db.record_play(tracks[0])
    db.completion(history, 0.8)
    db.completion(history, 0.2)
    with db.connect() as connection:
        assert connection.execute("SELECT completion FROM history").fetchone()[0] == 0.8


def test_failed_and_pending_downloads_are_not_local_library_items(db,audio_file):
    local=read_track(audio_file())
    pending=Track("deezer:pending","Pendiente",source="deezer")
    failed=Track("deezer:failed","Fallida",source="deezer")
    for track in (local,pending,failed):db.save_track(track)
    db.set_download(pending,"downloading",80)
    db.set_download(failed,"error",error="HTTP 403")
    assert [t.id for t in db.library_tracks()]==[local.id]
    assert len(db.tracks())==3  # Keep catalog identity and download history for explicit retry.


def test_completed_record_recovers_path_and_stale_catalog_cannot_erase_it(db,audio_file):
    remote=Track("deezer:recovered","Song",source="deezer")
    complete=replace(remote,path=str(audio_file()),duration=2)
    db.save_track(remote);db.set_download(complete,"complete",100)
    assert db.library_tracks()[0].path==complete.path
    db.save_track(replace(remote,path="/old/temporary/audio.mp3"))
    assert db.resolve_track(remote).path==complete.path
