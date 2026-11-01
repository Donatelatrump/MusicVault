"""Short-lived search results, scoped to the requesting guild and user."""
from dataclasses import dataclass
import time


@dataclass(eq=False)
class SearchResults:
    tracks: list
    expires: float
    view: object = None


class Searches:
    def __init__(self, ttl=120):
        self.ttl = ttl
        self.results = {}

    def save(self, key, tracks):
        now = time.monotonic()
        self.results = {k: v for k, v in self.results.items() if v.expires > now}
        if len(self.results) >= 1000:
            self.results.pop(next(iter(self.results)))
        result = SearchResults(list(tracks), now + self.ttl)
        self.results[key] = result
        return result

    def get(self, key, number, expected=None):
        result = self.results.get(key)
        if result is None or result.expires <= time.monotonic() or (expected is not None and result is not expected):
            raise ValueError("La búsqueda caducó o ya elegiste una canción. Usa /reproducir otra vez.")
        if not 1 <= number <= len(result.tracks):
            raise ValueError(f"Elige un número entre 1 y {len(result.tracks)}.")
        return result

    def consume(self, key, number, expected):
        result = self.get(key, number, expected)
        self.results.pop(key)
        return result.tracks[number - 1]
