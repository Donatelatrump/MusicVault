"""Private JVM bridge to the same NewPipeExtractor release as Android."""
import os
from pathlib import Path


def command(mode, query="", limit=25):
    root = Path(os.environ.get('MUSICVAULT_NEWPIPE_DIR',
                               Path(__file__).resolve().parents[1] / 'runtime/newpipe'))
    java = Path(os.environ.get('MUSICVAULT_NEWPIPE_JAVA', root / 'jre/bin/java.exe'))
    if not java.is_file() or not (root / 'bridge.jar').is_file():
        return None
    classpath = os.pathsep.join((str(root / 'bridge.jar'), str(root / 'lib/*')))
    return [str(java), '-Xmx192m', '-Dfile.encoding=UTF-8', '-cp', classpath,
            'MusicVaultNewPipe', mode, query, str(limit)]
