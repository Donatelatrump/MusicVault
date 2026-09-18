# Music Vault v8 — onboarding opaque + playlist modals

- El handoff inicial ya no usa alpha/fade sobre Inicio: la pantalla de carga es 100% opaca y desaparece solamente cuando Home está visualmente listo.
- `RecommendationLoadingScreen` usa un fondo sólido `VaultColors.Background`.
- El modal de crear playlist se rediseñó con el lenguaje visual Vault: encabezado con icono, superficie oscura, borde, campo de nombre, contador de selección, seleccionar todas/ninguna, portadas y CTA claro.
- El modal de revisión al importar una playlist de Spotify usa la misma familia visual, mostrando portada, conteo, selección masiva y filas con artwork.
- Las tarjetas de playlists ahora tienen un botón de opciones; las playlists normales se pueden eliminar con confirmación. Favoritos permanece protegido.
- La confirmación de eliminación aclara que borrar una playlist no elimina las canciones descargadas de la biblioteca.
