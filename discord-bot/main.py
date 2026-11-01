import sys

if __name__ == "__main__":
    if "--check" in sys.argv:
        import subprocess
        from musicvault_discord.runtime import prepare_runtime
        from musicvault_discord.provider import MusicProvider
        prepare_runtime()
        provider = MusicProvider()
        from musicvault.downloader import javascript_runtime, downloader_command
        import os
        subprocess.run([os.environ["MUSICVAULT_FFMPEG"], "-version"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(downloader_command() + ["--version"], check=True)
        if not javascript_runtime():
            raise SystemExit("Falta Node.js >=22 o Deno >=2.3.")
        print("Motor de MusicVault, SQLite, FFmpeg y runtime JavaScript disponibles. No se conectó a Discord.")
    else:
        from musicvault_discord.bot import main
        main()
