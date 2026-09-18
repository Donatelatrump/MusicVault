"""Cross-platform first-run taste profile and recommendation helpers."""
from __future__ import annotations

import unicodedata

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
