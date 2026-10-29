# MUGEN Android 0.9.3 — revisión de robustez

## Problemas corregidos

| Área | Fallo observado en el código | Cambio |
| --- | --- | --- |
| Biblioteca | Un JSON ilegible se convertía en una lista vacía, que podía sobrescribir la biblioteca. | Escrituras con `AtomicFile`, copia `library.backup.json`, recuperación y conservación del original dañado. Si no hay copia válida, las mutaciones fallan sin modificar los archivos. |
| Edición y progreso | La interfaz guardaba una lista completa que podía estar desactualizada. | Cada acción transforma el estado persistido bajo un bloqueo; la interfaz publica el resultado después de guardarlo. |
| Importación | Duplicados dentro de un lote, consultas cuadráticas, importaciones parciales presentadas como correctas y límite silencioso de cinco subcarpetas. | Deduplificación con conjuntos, recorrido con detección de ciclos y rutas completas; límite explícito de profundidad 64. Un error aborta el lote antes de guardarlo. |
| Cancelación | La exploración y preparación podían seguir trabajando tras salir de la pantalla. | Comprobaciones cooperativas, botón Cancel y un único trabajo de importación. El compromiso final de disco termina de forma atómica. |
| Lectores | Lecturas completas de entradas comprimidas y bitmaps sin límite de píxeles. | Límites sobre los bytes reales y el tamaño de bitmaps; recursos EPUB de hasta 16 MiB, metadatos de 4 MiB, imágenes de 32 MiB, caché por operación de 1 GiB y hasta 10 000 páginas. |
| Recursos | Cancelar después de abrir un libro podía perder su descriptor; cerrar y decodificar podían competir. | Apertura con transferencia explícita de propiedad y limpieza en cancelación; cierre idempotente y sincronizado con lectura/decodificación. |
| Portadas | Recorrer un ZIP secuencialmente descomprimía entradas innecesarias; un USB desconectado podía quedar marcado permanentemente como sin portada. | Consulta del directorio ZIP y distinción entre ausencia de portada y error temporal de acceso. |
| Actualizaciones | Un archivo previo impedía reintentar la descarga; al recrear la pantalla se perdía su seguimiento; cancelar una verificación podía borrar el APK. | Nombre por versión, reemplazo explícito, estado guardado y propagación de cancelación. Se comprueban paquete, versión y SHA-256. |

Los límites de lectores producen un error legible o descartan el recurso que no se puede cargar; los archivos originales permanecen en su ubicación. Los TXT demasiado grandes se rechazan en lugar de mostrarse truncados.

## Pruebas

Desde `android/`, con JDK 17, Gradle 8.9 y SDK 35:

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

Las pruebas de regresión cubren recuperación y corrupción de biblioteca, importación concurrente, progreso guardado, migración de etiquetas, duplicados, carpetas profundas y cíclicas, errores de acceso, cancelación, límites reales de streams, dimensiones extremas de imágenes y entradas ZIP de gran expansión. Se conservan las pruebas previas de Android Auto y versiones.

## Comprobaciones pendientes

- Teléfono físico: rotación durante descargas y lectura; cancelar una importación desde SD/USB; desconexión y reconexión del medio; reproducción en segundo plano.
- RAR4/RAR5/7z: la extracción depende de JNI/libarchive y requiere pruebas de dispositivo; Robolectric no ejecuta esa biblioteca nativa.
- Android Auto: detección, navegación, voz y reproducción en vehículo o Desktop Head Unit.
- Publicación: el artefacto de CI es de depuración. Una actualización de producción necesita la clave de firma compatible con la instalación existente y el flujo de publicación firmado.
- Los límites de cada lectura no constituyen una cuota global de caché. La extracción se cancela entre bloques; una llamada bloqueada dentro del proveedor de archivos depende de que el proveedor vuelva a responder.
