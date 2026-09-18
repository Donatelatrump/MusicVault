# MusicVault — recomendaciones y búsqueda v3

Cambios principales:

- El onboarding precarga recomendaciones inmediatamente al confirmar artistas.
- Las consultas de artistas/géneros se ejecutan en paralelo durante la animación.
- Las canciones y álbumes de artistas seleccionados se intercalan round-robin para que el límite del feed no quede monopolizado por un solo artista.
- La animación espera al lote completo y a que Inicio tenga canciones + álbumes (con timeout offline).
- Inicio, Buscar, Biblioteca y Álbumes permanecen montados y sus adapters pueden precalentarse bajo la animación.
- Buscar ahora muestra un selector segmentado `Canciones | Álbumes` solamente después de enviar una búsqueda, justo debajo del buscador, con conteos por tipo.
- Se corrigió el layout anterior de los tabs, donde ambos elementos usaban `fillMaxWidth()` dentro del mismo Row.

Validación local sugerida:

```bash
./gradlew :app:compileDebugKotlin -q --console=plain 2>&1 | tail -40
./gradlew clean assembleDebug
```
