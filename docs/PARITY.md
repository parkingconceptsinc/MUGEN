# MUGEN: revisión de paridad PWA / APK

Revisión de código sobre `3c10998b59b891ff2b7b2ed169595d908e4adb8a`.
Esta matriz distingue implementación de validación: encontrar una función en el código no acredita su funcionamiento en todos los dispositivos. No se ha alcanzado paridad total. La ampliación de Studio y Audio Lab en 0.15.0 está descrita en [CREATIVE_WORKSPACES.md](CREATIVE_WORKSPACES.md).

| Área | PWA | Android | Diferencias / siguiente trabajo |
| --- | --- | --- | --- |
| Importación | Archivos, carpetas, lotes y deduplicación; IndexedDB | Archivos y carpetas SAF, escaneo opcional, permisos persistentes | El navegador no puede escanear libremente el almacenamiento. Probar cancelación, cuota y permisos revocados por plataforma. |
| Biblioteca musical | Canciones, artistas, álbumes, géneros, carpetas, favoritos, recientes, historial, más escuchadas | Canciones, artistas, álbumes, carpetas, listas | Llevar géneros, historial y estadísticas a Android con migración de datos. |
| Listas / cola | Crear, renombrar, quitar, reordenar, reproducir siguiente | Listas persistentes y cola reordenable | Probar orden, duplicados, borrado y recuperación en ambas. No comparten datos entre dispositivos. |
| Reproducción | Audio/vídeo HTML, fallback de audio FFmpeg, Media Session | Media3, servicio de reproducción y Android Auto | Los códecs y la continuidad en segundo plano dependen del sistema. Probar Bluetooth, interrupciones y bloqueo. |
| Herramientas de reproducción | Velocidad, tono, balance, boost, A–B y temporizador con desvanecimiento | Velocidad, A–B, temporizador en servicio y DSP | Igualar rangos y conservación del tono. Verificar temporizador en segundo plano. |
| Ecualizador | 10 bandas, presets, preamplificador, balance y limitador | Audio Lab PCM, curvas, FFT, medidores y presets completos | La PWA incorpora filtros, compresor, headroom, limitador, delay, reverb y render WAV. Gate, afinación y efectos de carácter siguen siendo nativos. Motores/presets históricos no equivalen muestra a muestra. |
| Voz / sintetizador | Grabación, afinación offline, sintetizador y WAV | Grabación, afinación, transposición, sintetizador, secuenciador y WAV | Grabación con permiso explícito y render local añadidos. Afinación offline, transposición, sintetizador y secuenciador añadidos en 0.16.0; falta igualar procesamiento en tiempo real y efectos nativos de carácter. |
| Vídeo | Gestos, tamaño, brillo, PiP y pantalla completa | Reproductor nativo, pistas integradas y reanudación | Añadido fijar controles y ocultación a los cuatro segundos en PWA; falta validación táctil real. |
| Subtítulos | SRT/VTT | SRT/VTT e integrados | Añadidos desfase, dos referencias, deriva, exportación SRT y límites compatibles en PWA. Pendiente persistencia por vídeo y selector de pistas integradas en PWA. |
| Edición de vídeo | Studio con varios clips, trims, efectos, undo/redo, guardado y MP4 local | Studio: varios clips, recorte, efectos, velocidad, deshacer, proyectos y MP4/SRT | La PWA tiene proyecto y línea de tiempo propios; intercambio portable añadido en 0.16.0; faltan subtítulos del montaje y algunas herramientas nativas. |
| EPUB | epub.js, índice, temas, tipografía, progreso | Lector local, índice, temas, progreso, marcadores y búsqueda completa | Pendientes marcadores y búsqueda en PWA. Probar imágenes, UTF-16, capítulos no lineales y libros dañados. |
| Texto / documentos | TXT, Markdown, archivos estructurados, HTML, RTF, DOCX, ODT | TXT integrado en lector; ver lectores nativos | Portar formatos adicionales a Android con límites de descompresión y archivos de prueba. |
| Cómics | CBZ, CBR, PDF, modos paginado/doble/vertical, manga, zoom y progreso | CBZ/CBR/CB7/PDF, modos, zoom, progreso y marcadores | CB7 y marcadores pendientes en PWA; probar variantes RAR y consumo de memoria. |
| Fotos | Importación, álbumes, favoritos, selección y visor | Galería, álbumes, favoritos, selección y visor | La PWA copia/importa; Android usa acceso a galería. No equivalen los permisos ni el borrado. |
| Apariencia | Claro/oscuro, acentos, movimiento y cristal | Claro/oscuro/sistema y paletas | Igualar preferencia de sistema, accesibilidad y nombres de paleta; capturas a 360/768/1280 px pendientes. |
| Recuperación | Errores de importación/almacenamiento; nueva pantalla de recuperación React | Escritura atómica, respaldo, recuperación y reconexión SAF | Probar fallos de disco, datos dañados y migraciones. La pantalla React permite reiniciar sin borrar la biblioteca. |
| Actualizaciones | Service worker | Descarga APK, checksum, validación de paquete y versión | Nueva versión común y validación conjunta; instalación final sigue siendo distinta por plataforma. |

## Cambios de esta entrega

- `shared/release.json` define versión y código Android; PWA, Gradle y etiquetas de publicación se comprueban contra él. Se propone `0.16.0`, superior a las dos versiones anteriores.
- `validate.yml` compila y comprueba ambas apps desde el mismo commit. Pages espera a ambas. Las publicaciones `android-v*` comprueban la etiqueta, publican la APK firmada y después la PWA del mismo commit. Se mantienen los secretos de firma existentes. Los APK de validación son de depuración.
- La caché PWA cambia con el contenido de cada build, instala el shell completo o rechaza la actualización y evita mezclar HTML nuevo con recursos antiguos. No intercepta API, archivos externos, multimedia ni peticiones Range. La actualización espera a que el usuario elija reiniciar.
- Subtítulos PWA: UTF-8, UTF-16 y Windows-1252, validación, desfase, calibración con una/dos referencias, deriva, exportación SRT y cancelación de lecturas pendientes al cambiar de vídeo.
- Controles de vídeo fijables y pantalla de recuperación si falla el renderizado.

## Validación y límites

Ejecutados localmente: ocho casos automatizados de subtítulos y caché, `check:release` y `git diff --check`.

No ejecutados localmente: compilación PWA, compilación/lint Android y pruebas en dispositivo. La red del entorno rechaza la conexión al proxy, npm no dispone de todas las dependencias en caché y no hay Gradle/SDK Android configurados. CI queda preparado para ejecutarlos; su resultado debe revisarse antes de integrar.

No se ha publicado una PWA ni generado una APK firmada durante esta revisión. No se ha cambiado contenido de bibliotecas personales. Actualizar ambas apps en paralelo significa validar y publicar el mismo código de versión; no significa sincronizar automáticamente medios, progreso ni ajustes entre dispositivos.

## Criterios para completar la paridad

1. Resolver todas las diferencias funcionales de la matriz; mantener las restricciones de plataforma visibles y honestas.
2. Fixture compartido de biblioteca, listas, proyecto Studio, presets y subtítulos; importación/exportación reversible con versión de esquema.
3. Flujos completos en navegador instalado y Android físico: importación → reproducción/lectura/edición → guardado → cierre → recuperación.
4. Sin conexión, permisos denegados, cuota agotada, archivos dañados y cancelación sin pérdida de estado.
5. Capturas y revisión con teclado, lector de pantalla, movimiento reducido y tamaños de móvil/tablet/escritorio.
