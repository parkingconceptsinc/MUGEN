# MUGEN APK — aplicación Android nativa

Este módulo Android está en transición desde el antiguo lanzador WebView a una aplicación nativa con Kotlin, Jetpack Compose y Media3. La PWA permanece separada en la raíz del repositorio y no se modifica por esta aplicación.

## Compilar

Requisitos: Android Studio, JDK 17 y Android SDK 35. Desde la carpeta `android/`:

```sh
gradle assembleDebug
```

El APK de depuración se genera en `app/build/outputs/apk/debug/app-debug.apk`. El workflow de GitHub Actions también compila y publica el APK como artefacto descargable de la ejecución.

## Funciones

- Tema claro, oscuro o el del sistema, con los mismos colores que la PWA. La barra lateral (fija en tablets, deslizable en el móvil) conserva su imagen y sus letras brillantes en ambos modos.
- Biblioteca guardada en `library.json`: música, vídeos, libros y cómics desde archivos sueltos o carpetas enteras (teléfono, SD o USB). Los archivos no se copian; se guardan sus permisos de acceso, el progreso y las portadas.
- Música con carátulas y etiquetas, búsqueda, vistas por canciones, artistas, álbumes y carpetas, cola, aleatorio, repetición, velocidad y pantalla Reproduciendo con barra de posición. Reproducción con Media3 en un servicio aparte.
- Vídeos con miniaturas y reproductor a pantalla completa.
- Libros: lector EPUB con imágenes y estilos del libro, índice, tamaño de letra, tipografía, interlineado, temas (oscuro, negro, sepia y claro), páginas o lectura continua, y progreso guardado. Los TXT usan el mismo lector.
- Cómics: CBZ, CBR (RAR4 y RAR5), CB7 y PDF con modos página, doble página y vertical, dirección manga, ajuste a pantalla o a ancho, zoom, cuadrícula de páginas y progreso guardado. Las estanterías tienen "Seguir leyendo", búsqueda, orden, series por carpeta y menú para marcar como leído, mover o quitar.
- Fotos tipo álbum desde la galería del teléfono, con álbumes, favoritos, selección múltiple y visor con zoom.
- Ecualizador de 10 bandas con los ajustes predefinidos de la PWA y ajustes propios, preamplificador, balance y refuerzo de volumen.
- Acceso directo a Spotify y servicio de Android Auto.

## Estado y limitaciones

### 0.10.0 (versionCode 1000)

- Vídeo a pantalla completa con superficie TextureView, controles nativos, botón permanente para mostrarlos y opción de fijarlos. Ajuste de imagen, velocidades, navegación entre vídeos, subtítulos integrados y reanudación por archivo.
- Subtítulos externos SRT/WebVTT, ajuste manual en milisegundos y sincronización automática por una referencia (desfase) o dos (desfase y deriva). Se siguen la posición real del vídeo, los saltos y la velocidad.
- Editor básico: recorte, giro, silencio y resolución original/720p/1080p. Exporta una copia MP4 mediante Media3 Transformer y permite cancelar y elegir destino.
- Audio con repetición A–B, temporizador de apagado en el servicio, velocidad recordada y reordenación de cola.
- Marcadores por documento en libros y cómics, búsqueda en el capítulo actual de EPUB/TXT, salto directo a página y guardado de progreso al salir o pasar a segundo plano.

Consulta [MEDIA_STUDIO.md](MEDIA_STUDIO.md) para uso, alcance y pruebas.

### 0.9.3 (versionCode 903)

- Biblioteca con escritura atómica, copia de respaldo y recuperación del último estado válido. Si ambas copias están dañadas, se bloquea la escritura y se muestra el error para evitar pérdidas silenciosas.
- Cambios de biblioteca sobre el estado persistido más reciente; importaciones sin duplicados, cancelables y con progreso. Los errores de acceso a carpetas ya no se presentan como una importación vacía correcta.
- Lectores y portadas con límites de memoria y caché, limpieza de recursos al cancelar y protección frente a cierres durante una decodificación.
- Las descargas de actualización conservan su estado al recrearse la pantalla, permiten reintentos y comprueban el paquete y la versión además del checksum.

Consulta [ROBUSTNESS_REVIEW.md](ROBUSTNESS_REVIEW.md) para el alcance, pruebas y comprobaciones pendientes en dispositivos.

### 0.9.2 (versionCode 902)

- Las órdenes de voz de Android Auto se resuelven también al reemplazar la cola. Una búsqueda selecciona la canción encontrada; una orden vacía usa la última canción reproducida o la primera disponible.
- Al descartar una petición que no se puede reproducir, se conserva la canción seleccionada y su posición. Si se descarta la propia selección, se usa la siguiente canción disponible, o la última, desde su posición predeterminada.
- Pruebas de regresión para búsquedas, órdenes vacías, navegación por identificador, selección de cola y bibliotecas vacías.

El ecualizador usa el del sistema Android, que en la mayoría de teléfonos tiene 5 bandas: las 10 de la app se adaptan a ellas. Faltan pruebas en un vehículo con Android Auto.

La app no usa WebView ni carga la PWA. No se afirma que el APK esté listo para distribución final hasta que el workflow compile correctamente y las funciones se prueben en dispositivos reales.
