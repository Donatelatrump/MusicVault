# MusicVault v6 — visual onboarding handoff

- The recommendation animation now remains above the app until Home has the final recommendation data **and** the Home RecyclerView has committed those rows and reached a real pre-draw.
- Home artwork is pre-warmed with the exact Coil cache keys/sizes used by the UI before the handoff: up to 8 track covers and 6 album covers.
- Artwork warmup has a longer bounded window (6.5 s) so normal mobile networks can finish without revealing a half-painted Home.
- The final overlay fade has only a tiny 120 ms frame cushion instead of an arbitrary long delay.
- There is still an 18 s safety timeout so an offline/broken image host cannot trap a new user forever.
- Existing Home content can satisfy the visual-ready callback even if no new DiffUtil update is needed.

The important distinction is that this waits for the content visible in the initial Home experience, not every off-screen recommendation in the full feed. Waiting for dozens of off-screen covers would only make onboarding slower without improving the first frame the user sees.
