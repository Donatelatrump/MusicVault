"""Cross-platform first-run taste profile and recommendation helpers."""
from __future__ import annotations

import time
import unicodedata
from dataclasses import dataclass, field

GENRE_OPTIONS = (
    "Pop", "Rock", "Alternativo", "Indie", "Hip-hop", "Rap en español", "Trap", "R&B",
    "Reggaetón", "Corridos tumbados", "Regional mexicano", "Pop latino", "Cumbia",
    "Salsa / Bachata", "EDM", "House", "Techno", "Metal", "Punk", "Funk / Disco",
    "K-pop", "J-pop", "Jazz / Soul", "Clásica", "Lo-fi / Ambient", "Soundtracks",
)

ARTISTS_BY_GENRE = {
    "Pop": ("Taylor Swift", "The Weeknd", "Billie Eilish", "Dua Lipa", "Ariana Grande", "Bruno Mars", "Lady Gaga", "Sabrina Carpenter"),
    "Rock": ("Linkin Park", "Foo Fighters", "Green Day", "Red Hot Chili Peppers", "Queen", "Nirvana", "The Killers", "Måneskin"),
    "Alternativo": ("Arctic Monkeys", "Radiohead", "Tame Impala", "The Neighbourhood", "Twenty One Pilots", "Paramore", "Gorillaz", "Muse"),
    "Indie": ("Arctic Monkeys", "Tame Impala", "Lana Del Rey", "Mac DeMarco", "Cigarettes After Sex", "The Strokes", "Clairo", "Vampire Weekend"),
    "Hip-hop": ("Kendrick Lamar", "Travis Scott", "Drake", "Tyler, The Creator", "Eminem", "J. Cole", "Kanye West", "21 Savage"),
    "Rap en español": ("Duki", "Trueno", "Wos", "Canserbero", "Santa Fe Klan", "Alemán", "C. Tangana", "Residente"),
    "Trap": ("Travis Scott", "Future", "Metro Boomin", "Playboi Carti", "Duki", "Anuel AA", "Eladio Carrión", "Bad Bunny"),
    "R&B": ("The Weeknd", "SZA", "Frank Ocean", "Brent Faiyaz", "Giveon", "Daniel Caesar", "Summer Walker", "Miguel"),
    "Reggaetón": ("Bad Bunny", "Feid", "Rauw Alejandro", "Karol G", "J Balvin", "Myke Towers", "Young Miko", "Daddy Yankee"),
    "Corridos tumbados": ("Peso Pluma", "Natanael Cano", "Junior H", "Fuerza Regida", "Tito Double P", "Luis R Conriquez", "Gabito Ballesteros", "Óscar Maydon"),
    "Regional mexicano": ("Carín León", "Christian Nodal", "Grupo Firme", "Banda MS", "Julión Álvarez", "Edén Muñoz", "Calibre 50", "La Arrolladora"),
    "Pop latino": ("Shakira", "Morat", "Sebastián Yatra", "Camilo", "Luis Miguel", "ROSALÍA", "Juanes", "Carlos Vives"),
    "Cumbia": ("Los Ángeles Azules", "Selena", "Grupo Cañaveral", "Los Askis", "Sonora Dinamita", "Raymix", "Celso Piña", "Kumbia Kings"),
    "Salsa / Bachata": ("Romeo Santos", "Aventura", "Marc Anthony", "Prince Royce", "Víctor Manuelle", "Héctor Lavoe", "Juan Luis Guerra", "Frankie Ruiz"),
    "EDM": ("David Guetta", "Calvin Harris", "Avicii", "Martin Garrix", "Tiësto", "Marshmello", "Zedd", "Swedish House Mafia"),
    "House": ("Fred again..", "Disclosure", "Peggy Gou", "FISHER", "Dom Dolla", "Purple Disco Machine", "Kaytranada", "Jamie xx"),
    "Techno": ("Charlotte de Witte", "Amelie Lens", "Carl Cox", "Adam Beyer", "Nina Kraviz", "Boris Brejcha", "Anyma", "Paul Kalkbrenner"),
    "Metal": ("Metallica", "System Of A Down", "Slipknot", "Deftones", "Bring Me The Horizon", "Rammstein", "Avenged Sevenfold", "Iron Maiden"),
    "Punk": ("blink-182", "Green Day", "The Offspring", "Sum 41", "Paramore", "Simple Plan", "Sex Pistols", "Bad Religion"),
    "Funk / Disco": ("Daft Punk", "Michael Jackson", "Earth, Wind & Fire", "Jamiroquai", "Chic", "Parcels", "Bee Gees", "Stevie Wonder"),
    "K-pop": ("BTS", "BLACKPINK", "Stray Kids", "TWICE", "aespa", "NewJeans", "SEVENTEEN", "LE SSERAFIM"),
    "J-pop": ("YOASOBI", "Ado", "Kenshi Yonezu", "Fujii Kaze", "LiSA", "Vaundy", "King Gnu", "Mrs. GREEN APPLE"),
    "Jazz / Soul": ("Laufey", "Amy Winehouse", "Norah Jones", "Stevie Wonder", "Etta James", "Marvin Gaye", "Miles Davis", "Nina Simone"),
    "Clásica": ("Ludovico Einaudi", "Frédéric Chopin", "Ludwig van Beethoven", "Antonio Vivaldi", "Max Richter", "Yo-Yo Ma", "Claude Debussy", "Wolfgang A. Mozart"),
    "Lo-fi / Ambient": ("Nujabes", "Tycho", "Bonobo", "idealism", "Ólafur Arnalds", "Boards of Canada", "Emancipator", "Jinsang"),
    "Soundtracks": ("Hans Zimmer", "Joe Hisaishi", "John Williams", "Ramin Djawadi", "Ludwig Göransson", "Hiroyuki Sawano", "Howard Shore", "Michael Giacchino"),
}


def normalize(value: str) -> str:
    value = unicodedata.normalize("NFKD", (value or "").casefold())
    return "".join(ch for ch in value if not unicodedata.combining(ch) and ch.isalnum())


def suggested_artists(genres, limit: int = 36):
    score = {}
    for genre in genres:
        for index, artist in enumerate(ARTISTS_BY_GENRE.get(genre, ())):
            score[artist] = score.get(artist, 0) + 100 - index * 3
    return [name for name, _ in sorted(score.items(), key=lambda item: (-item[1], item[0].casefold()))[:limit]]


def round_robin(buckets, limit: int = 40):
    """Interleave buckets so one artist cannot monopolize recommendation rails."""
    queues = [list(bucket) for bucket in buckets if bucket]
    out, seen = [], set()
    while queues and len(out) < limit:
        next_queues = []
        for queue in queues:
            while queue:
                value = queue.pop(0)
                key = getattr(value, "id", None) or repr(value)
                if key not in seen:
                    out.append(value)
                    seen.add(key)
                    break
            if queue:
                next_queues.append(queue)
            if len(out) >= limit:
                break
        queues = next_queues
    return out


def artist_matches(candidate: str, selected: str) -> bool:
    a, b = normalize(candidate), normalize(selected)
    return bool(a and b and (a == b or a in b or b in a))


def taste_signature(genres, artists) -> str:
    left = "|".join(sorted({g.strip() for g in genres if g.strip()}, key=str.casefold))
    right = "|".join(sorted({a.strip() for a in artists if a.strip()}, key=str.casefold))
    return f"{left}::{right}"


JAM_MIX_ID = "recommendation:dj"


@dataclass
class DjProfile:
    artists: dict
    tracks: dict
    recent: set
    genres: dict
    preferred_artists: set


@dataclass
class DjUiState:
    active: bool = False
    loading: bool = False
    reason: str = "dj_reason_learning"
    upcoming: list = field(default_factory=list)
    message: str | None = None


class DjRecommendationEngine:
    """Port of Android DjRecommendationEngine: taste-driven jam mix with 21-day decay."""

    def __init__(self):
        self.discovered = []
        self.discovered_seeds = []
        self.discovered_at = 0

    def profile(self, history, known, preferred_artists, now):
        by_id = {t.id: t for t in known}
        artists = {}
        songs = {}
        genres = {}
        for play in history:
            days = max(0, now - play["played_at"]) / 86_400_000.0
            decay = 0.5 ** (days / 21.0)
            ratio = play.get("completion_ratio", 0.0)
            if ratio < 0:
                evidence = -5.0
            elif ratio >= 0.9:
                evidence = 8.0
            elif ratio >= 0.8:
                evidence = 6.0
            elif ratio >= 0.6:
                evidence = 4.0
            elif ratio >= 0.3:
                evidence = 2.0
            elif ratio >= 0.15:
                evidence = 1.0
            else:
                evidence = 0.0
            evidence *= decay
            track_id = play["track_id"]
            songs[track_id] = songs.get(track_id, 0.0) + evidence
            track = by_id.get(track_id)
            if track:
                artist = track.artist
                if artist:
                    key = normalize(artist)
                    artists[key] = artists.get(key, 0.0) + evidence
                album = track.album
                if album:
                    key = normalize(album)
                    genres[key] = genres.get(key, 0.0) + evidence * 0.5
        recent = {p["track_id"] for p in history if now - p["played_at"] < 6 * 3_600_000}
        return DjProfile(artists, songs, recent, genres, set(preferred_artists))

    def recommendations(self, history, known, preferred_artists, excluded, current, online, playable_offline, discover_fn=None, convert=None):
        if convert is None:
            convert = lambda t: t
        now = time.time() * 1000
        taste = self.profile(history, known, preferred_artists, now)
        names = {normalize(t.artist): t.artist for t in known}
        learned = sorted(
            ((a, v) for a, v in taste.artists.items() if v > 0),
            key=lambda item: -item[1],
        )
        learned_names = []
        for a, _ in learned[:5]:
            n = names.get(a)
            if n:
                learned_names.append(n)
        preferred_set = set(preferred_artists)
        seeds = list(dict.fromkeys(learned_names + list(preferred_set)))[:5]

        if online and seeds and discover_fn and (
            self.discovered_seeds != seeds or now - self.discovered_at > 15 * 60_000
        ):
            fresh = []
            for seed in seeds[:3]:
                try:
                    fresh += discover_fn(seed, 25)
                except Exception:
                    pass
            if fresh:
                seen_keys = set()
                unique = []
                for t in fresh:
                    key = f"{getattr(t, 'source', 'local')}:{t.id}"
                    if key not in seen_keys:
                        seen_keys.add(key)
                        unique.append(t)
                self.discovered = unique
                self.discovered_seeds = seeds
                self.discovered_at = now

        pool = [convert(t) for t in self.discovered] if online else []
        pool += list(known)

        def song_key(track):
            return normalize(track.artist) + ":" + normalize(track.title)

        current_id = current.id if current else None
        excluded_songs = {
            song_key(t)
            for t in known
            if t.id in excluded or t.id == current_id
        }

        unique_pool = {}
        for t in pool:
            unique_pool[t.id] = t

        ranked = sorted(
            (
                t
                for t in unique_pool.values()
                if t.id not in excluded
                and t.id != current_id
                and song_key(t) not in excluded_songs
                and (online or t.id in playable_offline)
                and taste.tracks.get(t.id, 0.0) > -2.0
            ),
            key=lambda t: (
                taste.artists.get(normalize(t.artist), 0.0)
                + taste.genres.get(normalize(t.album), 0.0) * 0.5
                + (15.0 if t.artist in taste.preferred_artists else 0.0)
                + (15.0 if t.id not in taste.tracks else 0.0)
                + (-25.0 if t.id in taste.recent else 0.0)
                + (-10.0 if normalize(t.artist) == normalize(current.artist if current else "") else 0.0)
                + max(-20.0, min(10.0, taste.tracks.get(t.id, 0.0)))
            ),
            reverse=True,
        )

        groups = {}
        for t in ranked:
            groups.setdefault(normalize(t.artist), []).append(t)
        groups = list(groups.values())
        max_size = max((len(g) for g in groups), default=0)
        result = []
        for index in range(max_size):
            for group in groups:
                if index < len(group):
                    result.append(group[index])
        result = result[:40]

        if not online:
            reason = "dj_reason_offline"
        elif learned_names:
            reason = " · ".join(learned_names[:2])
        elif preferred_artists:
            reason = " · ".join(list(preferred_artists)[:2])
        else:
            reason = "dj_reason_learning"

        return result, reason
