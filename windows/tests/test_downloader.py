from pathlib import Path

from musicvault import downloader


def test_desktop_runtime_finds_nvm_without_shell_path(tmp_path,monkeypatch):
    binary=tmp_path/".nvm/versions/node/v24.1.0/bin/node"
    binary.parent.mkdir(parents=True)
    binary.write_text("#!/bin/sh\nprintf 'v24.1.0\\n'\n")
    binary.chmod(0o755)
    monkeypatch.setattr(downloader.Path,"home",classmethod(lambda cls:tmp_path))
    monkeypatch.setattr(downloader.shutil,"which",lambda name:None)
    downloader.javascript_runtime.cache_clear()
    try:
        assert downloader.youtube_arguments()==["--js-runtimes","node:"+str(binary)]
    finally:
        downloader.javascript_runtime.cache_clear()


def test_http_failure_explains_that_no_audio_was_saved():
    message=downloader.explain_download_error("WARNING: optional hint\nERROR: HTTP Error 403: Forbidden")
    assert "403" in message and "No se guardó ningún audio" in message


def test_dns_errors_in_new_and_old_records_are_readable():
    raw = "query: Unable to download API page: HTTPSConnection(host='www.youtube.com', port=443): Failed to resolve 'www.youtube.com' ([Errno -3] Temporary failure in name resolution)"
    for prefix in ("ERROR: ", "No se pudo descargar: ", ""):
        message = downloader.explain_download_error(prefix + raw)
        assert "DNS" in message and "Reintentar" in message
        assert "HTTPSConnection" not in message and len(message) < 160
        assert downloader.explain_download_error(message) == message
    assert downloader.explain_download_error("") == ""


def test_timeout_and_unknown_errors_do_not_expose_tracebacks():
    assert "conexión" in downloader.explain_download_error("ERROR: connection timed out")
    assert "detalles" in downloader.explain_download_error("ERROR: Traceback: extractor failed")
