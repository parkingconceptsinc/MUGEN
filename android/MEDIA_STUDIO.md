# MUGEN 0.12.1: vídeo, audio y lectura

## Vídeo a pantalla completa

La pantalla anterior desactivaba los controles de `PlayerView` y dependía de una capa táctil Compose sobre la superficie de vídeo. Ahora el reproductor usa `TextureView` y controles nativos dentro de su propia vista. «Show controls» está en la barra superior y «Controls pinned» evita que se oculten al tocar o esperar. La barra se desplaza horizontalmente cuando el espacio es pequeño.

Se incluyen reproducción/pausa, saltos de diez segundos, búsqueda en la línea de tiempo, posición/duración, buffering, velocidades, ajuste a pantalla/recorte/estiramiento, repetición y vídeo anterior/siguiente. El vídeo recuerda su posición al cerrar. La actividad mantiene su estado durante cambios de orientación y tamaño.

## Subtítulos

«Subtitles» permite elegir SRT o WebVTT. Se admiten UTF-8, UTF-16 con BOM y Windows-1252. Los subtítulos integrados se seleccionan desde el botón nativo CC; al activar una pista externa se evita mostrar simultáneamente las integradas.

- Ajuste manual: pasos de ±100/500 ms o segundos escritos. Positivo significa mostrar más tarde.
- Una referencia: pausa cuando se pronuncia una frase, selecciónala en la lista y pulsa «Set reference 1». Se corrige el desfase de todas las frases.
- Dos referencias: reproduce o avanza hasta otra frase posterior, selecciónala y pulsa «Set reference 2». Se calcula una transformación lineal para corregir desfase y deriva. Las referencias deben estar separadas al menos un segundo; se rechazan escalas fuera de 0.5–2.
- «Reset» devuelve desfase cero y escala uno. Los ajustes y el archivo se recuerdan por vídeo.

La sincronización por referencias no utiliza reconocimiento de voz. El texto se muestra según la posición del medio, por lo que sigue los cambios de velocidad y los saltos. El formato ASS/SSA y la incrustación de subtítulos en el MP4 exportado no están implementados.

«Save corrected SRT» guarda una copia UTF-8 con desfase y deriva ya aplicados. Se descartan frases que terminan antes de cero, se recortan las que lo cruzan y se conservan las superpuestas. Desde el editor, el SRT se limita al fragmento seleccionado y sus tiempos se cuentan desde el inicio de ese fragmento. Es un archivo separado del MP4; no se queman los subtítulos en la imagen.

## MUGEN Studio: pestaña Editor

«Editor» es una sección propia en la barra de pestañas y en el menú lateral. «Open in Editor», desde un vídeo, lleva su archivo y cabezal al estudio. El espacio se organiza en medios/proyectos, visor, línea de tiempo e inspector, tomando como referencia el flujo de [DaVinci Resolve](https://www.blackmagicdesign.com/products/davinciresolve/edit). En tablet se muestran visor e inspector en paralelo; en teléfono vertical se apilan. «Expand viewer» deja más espacio para revisar el montaje. Los textos del editor están en inglés, como el resto de los controles de la aplicación.

La importación comprueba primero el permiso y la apertura del archivo. Si Android retiró el acceso, «Reconnect folder» permite concederlo de nuevo a la carpeta original y reintenta la importación. «Retry import» y «Select video again» permiten recuperarse también de permisos de archivos sueltos revocados. Una importación fallida conserva el proyecto anterior completo. El aviso «Reconnect folders» recupera el acceso de la biblioteca sin cambiar sus rutas ni el progreso. Los metadatos usan MediaExtractor como alternativa cuando MediaMetadataRetriever no reconoce un contenedor; si tampoco se encuentra una pista de vídeo válida, se explica el fallo.

- **Proyectos y medios:** importar vídeos de la biblioteca o del selector Android, añadir clips, renombrar proyectos y reabrirlos. Los proyectos tienen escritura atómica y respaldo independiente. Si ambos son ilegibles, se impide sobrescribirlos. Los originales no se copian ni modifican.
- **Montaje:** hasta 64 clips secuenciales, dividir en el cabezal, duplicar, eliminar y mover a izquierda/derecha. La eliminación cierra el hueco. La línea de tiempo tiene zoom 1–16×, desplazamiento horizontal, selección y búsqueda por cabezal. Las bandas V1/A1 representan el vídeo y el estado del audio; no son una forma de onda medida.
- **Recorte preciso:** entrada/salida con formato `hh:mm:ss.mmm` o segundos, selector de rango y marcas en el cabezal. Deben quedar al menos 0,5 s de fuente. Un arrastre crea una sola operación de deshacer. Los tiempos de fuente se distinguen del reloj del montaje; las marcas de cabezal se desactivan fuera del clip seleccionado.
- **Transporte:** reproducción/pausa, inicio, paso nominal de fotograma y bucle opcional del proyecto. La frecuencia viene de la pista de vídeo; si falta se informa de una estimación de 30 fps. Fuentes de frecuencia variable no garantizan un avance de fotograma exacto por este método.
- **Inspector:** cortes y velocidad 0,25–4× conservando tono, giros explícitos 0/90/180/270°, espejo, brillo, contraste, saturación, silencio y volumen 0–100%. Al terminar un ajuste se pausa y actualiza la vista previa, conservando el fotograma de fuente cuando sigue dentro del rango. No hay un segundo botón de aplicar.
- **Historial:** deshacer/rehacer hasta 50 operaciones completas. La selección no añade operaciones al historial.
- **Subtítulos:** carga y sincronización por referencias/manual sobre el tiempo de fuente. La exportación SRT del montaje aplica calibración, recorte, orden de clips y velocidad; sigue siendo un archivo separado.
- **Exportación:** lienzo común de fuente/16:9/9:16/1:1 y altura 720p/1080p, preservando imagen con barras. «Crear MP4» renderiza el montaje H.264/AAC; «Guardar MP4» elige el destino. Los clips mudos producen silencio cuando otro clip tiene audio. Los cambios posteriores obligan a regenerar el MP4. La navegación avisa antes de cancelar un render y no permite interrumpir el guardado.

Vista previa y exportación comparten rangos, efectos visuales y lienzo. ExoPlayer aplica la velocidad al transporte; Transformer cambia los tiempos y procesa el audio, evitando aplicar la velocidad dos veces a la vista previa. Los saltos rápidos se agrupan y siempre prevalece el último solicitado. Al pasar entre clips puede haber una breve preparación del decodificador; no se promete reproducción sin pausas. El render requiere mantener Editor abierto. Los límites de codificación dependen del dispositivo y los errores permiten reintentar con otro tamaño.

Límites de almacenamiento: 50 proyectos, 64 clips por proyecto, 24 horas de duración final y 4 MiB por proyecto; subtítulos de entrada hasta 4 MiB por archivo y 16 MiB en conjunto, 50000 frases y 4 MiB de SRT final. Son cortes secuenciales: no se implementan pistas superpuestas, transiciones, keyframes, títulos animados ni subtítulos quemados. El autoguardado incluye los ajustes y cabezal, con escrituras serializadas al pasar a segundo plano o salir; deshacer/rehacer pertenece a la sesión abierta.

## Audio

La pantalla Reproduciendo incorpora puntos A y B, limpieza del bucle, temporizadores de 15/30/60/90 minutos y cancelación del temporizador. El servicio aplica bucle y temporizador aunque la pantalla no esté abierta. La cola permite subir pistas y eliminar las que no están sonando. Se conserva la velocidad de audio preferida y la reanudación del servicio usa una posición guardada. Las posiciones cercanas al final se reinician.

«Playlists», en Música y Reproduciendo, permite guardar la cola de audio actual con nombre, reproducirla, renombrarla y eliminarla. Se conservan orden y repeticiones. «Resume last queue» recupera la cola, pista seleccionada y posición guardada; también se usa al reanudar la sesión desde Android. La cola se captura en el hilo del reproductor y se escribe en segundo plano, sin reescribirla en cada actualización de posición. Las colas de vídeo o mezcladas no reemplazan la última cola de música.

Las listas y la cola tienen archivos atómicos y copias independientes de recuperación. Si ambas copias son ilegibles, se informa del error y no se sobrescriben con datos vacíos. Límites: 100 listas, 10000 pistas por lista/cola, 50000 pistas entre listas y 16 MiB por archivo. Se guardan referencias locales, no copias de canciones: si se revoca un permiso o desaparece un archivo, hay que volver a añadirlo.

## Libros y cómics

Ambos lectores tienen marcadores separados por documento. Se pueden guardar, abrir y eliminar sin duplicar posiciones. El progreso se guarda con retardo corto durante la lectura y se envía también al salir o al pasar a segundo plano. Las escrituras se serializan y los errores de guardado se muestran.

Los cómics incluyen salto directo a número de página; mantienen manga, doble página, vertical, zoom y cuadrícula. La caché se adapta a tamaño de pantalla y modo de ajuste. Los libros conservan temas, tipografía, índice y progreso. «Find in book» ofrece capítulo actual con anterior/siguiente y «Whole book» con resultados por capítulo. Al abrir un resultado se resalta su capítulo; puedes volver a «Current chapter» para recorrer las coincidencias.

La búsqueda global es literal, ignora mayúsculas y procesa un capítulo a la vez en segundo plano. Requiere dos caracteres, espera 350 ms después de escribir y se cancela al cambiar consulta o cerrar. Se limita a 64 MiB descomprimidos o 500 coincidencias; un aviso señala resultados parciales o capítulos ilegibles. No busca texto dentro de imágenes ni PDF.

## Validación y pruebas de dispositivo

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

Las pruebas cubren SRT/WebVTT, texto y codificaciones, frases superpuestas, desplazamientos manuales, calibración por referencias, deriva, saltos, límites de archivos, controles nativos y TextureView, parámetros de edición, marcadores, posición final y bucles A–B, además de las regresiones anteriores.

Para probar decodificación y exportación reales sin abrir una actividad ni modificar la biblioteca del usuario:

```sh
gradle connectedDebugAndroidTest -PdevicePreview=true
```

`MediaPipelineDeviceTest` usa un clip sintético incluido en los recursos de prueba; comprueba fotogramas de vista previa con giro y tamaño, duración del recorte y exportación sin pista de audio. No pide foco de audio y limpia los archivos temporales. El dispositivo debe admitir los códecs y efectos solicitados.

Verificación local de 0.12.0: 90 pruebas unitarias correctas, lint sin errores y cinco pruebas de dispositivo correctas en Samsung SM-X900 con Android 16/API 36, usando Preview. Se prueban el montaje con velocidades distintas y audio tras un primer clip mudo, el último salto en una secuencia rápida y la conservación del fotograma al cambiar ajustes. El MP4 sintético se decodificó además fuera de Android: RMS cero en la introducción muda y audio presente en el segundo clip.

Verificación de 0.12.1: 96 pruebas unitarias, lint sin errores y las cinco pruebas de dispositivo correctas. La prueba de interfaz empieza sin proyecto: importa un archivo inaccesible, comprueba las acciones de recuperación y después carga un clip sintético por el flujo normal del editor. Una comprobación manual optativa importa los metadatos de un vídeo real sin mostrar imágenes ni escribir proyectos o biblioteca. El auxiliar de migración de carpetas también es optativo: exige autorización explícita, tres raíces concretas que Preview ya tenga concedidas y un receptor con la misma firma. Solo existe en androidTest; no se ejecuta en las pruebas ordinarias ni añade una puerta de acceso al APK distribuido.

La prueba de interfaz usa una pantalla virtual secundaria con contenido sintético, sin abrir la biblioteca del usuario ni guardar proyectos. Verifica dividir/deshacer/rehacer, inspector, giro y visor ampliado, y guarda capturas a 1280×800, 420×900 y 860×440. Requiere Android 14/API 34 y pantallas secundarias; la actividad de prueba solo existe en debug, no está exportada, exige el identificador de su pantalla virtual y se cierra si cambia de pantalla. La prueba cierra su actividad y espera su destrucción antes de retirar la pantalla, también si falla una aserción. Estas capturas se revisaron visualmente; no constituyen una prueba de todos los gestos ni de todos los formatos personales.

Para probar sin sustituir una instalación existente con otra firma:

```sh
gradle assembleDebug -PdevicePreview=true
```

Esto genera `com.parkingconcepts.mortimer.preview`, con nombre «MUGEN Preview» y almacenamiento independiente. El APK normal conserva `com.parkingconcepts.mortimer`. Las claves de depuración de diferentes equipos o ejecuciones de CI pueden ser distintas: Android no admite una actualización normal entre ellas. Una migración de firma requiere consentimiento y copia de seguridad; restaurar los datos privados no restaura por sí solo los permisos SAF concedidos a la instalación anterior.
