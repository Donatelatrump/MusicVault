# Onboarding v9 — catálogo local de artistas

- El listado de artistas por género es local y no depende de red.
- Las sugerencias para la pantalla 2 se calculan mientras el usuario todavía está en la pantalla de géneros.
- La pantalla de artistas usa LazyColumn, por lo que solo compone las tarjetas visibles y no las 36 de golpe.
- Se redujo la duración de la transición entre Géneros -> Artistas para que se perciba inmediata.
- Las búsquedas reales de canciones/álbumes siguen ocurriendo únicamente después de que el usuario confirma sus artistas.
