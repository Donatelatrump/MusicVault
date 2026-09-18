# MusicVault v14 — descarga unificada + listas optimizadas

- Búsqueda: reemplazado el spinner circular de puntos por la misma barra horizontal delgada de progreso usada en las filas nativas de Inicio/Biblioteca.
- Estados de descarga Compose heredados: unificados a barras lineales delgadas, determinadas cuando existe porcentaje e indeterminadas para descargas por lote.
- Eliminado el uso de `LazyColumn` del código de la app.
- Importación de Spotify: lista de canciones migrada a `RecyclerView` nativo de altura fija y reciclado real.
- Letras sincronizadas: migradas a `RecyclerView` nativo, conservando foco de estrofa, opacidad/escala/desenfoque y auto-scroll.
- Cola de reproducción: migrada a `RecyclerView` nativo.
- Ajustes y selectores pequeños: usan `Column + verticalScroll`, evitando crear otra lista lazy para colecciones reducidas.
- Version: 0.5.5 (versionCode 16).
