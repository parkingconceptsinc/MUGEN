# Mortimer Player para Android nativo

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
- Cómics: CBZ, CBR (RAR4) y PDF con modos página, doble página y vertical, dirección manga, ajuste a pantalla o a ancho, zoom, cuadrícula de páginas y progreso guardado. Las estanterías tienen "Seguir leyendo", búsqueda, orden, series por carpeta y menú para marcar como leído, mover o quitar.
- Fotos tipo álbum desde la galería del teléfono, con álbumes, favoritos, selección múltiple y visor con zoom.
- Acceso directo a Spotify y servicio de Android Auto.

## Estado y limitaciones

Los CBR en formato RAR5 todavía no se pueden abrir; hay que convertirlos a CBZ. Falta el ecualizador de la PWA y las pruebas en un vehículo con Android Auto.

La app no usa WebView ni carga la PWA. No se afirma que el APK esté listo para distribución final hasta que el workflow compile correctamente y las funciones se prueben en dispositivos reales.
