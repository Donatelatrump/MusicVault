"""KataBump entry point: verify WARP before starting MusicVault."""
import os
from musicvault_discord.warp import WarpProxy, PROXY

if __name__ == '__main__':
    os.umask(0o077)
    with WarpProxy():
        os.environ['MUSICVAULT_MEDIA_PROXY'] = PROXY
        from musicvault_discord.bot import main
        main()
