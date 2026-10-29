# MUGEN 0.10.0: vídeo, audio y lectura

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

## Editor básico

Desde «Edit video», selecciona inicio y final, giro de 0/90/180/270 grados, eliminación de audio y tamaño original/720p/1080p. «Create MP4» procesa una copia con H.264/AAC; «Save MP4» abre el selector de destino de Android. Nunca se modifica el archivo original. Se muestra progreso, se permite cancelar y se limpian salidas incompletas. Mantén abierta la pantalla durante el procesamiento; cerrar el editor cancela la exportación.

Los límites reales de códecs, resolución y HDR dependen del dispositivo. Se permite fallback del codificador y se muestra un error para reintentar con tamaño original o un recorte más corto. No se incluyen montaje de varias pistas ni exportación de subtítulos quemados.

## Audio

La pantalla Reproduciendo incorpora puntos A y B, limpieza del bucle, temporizadores de 15/30/60/90 minutos y cancelación del temporizador. El servicio aplica bucle y temporizador aunque la pantalla no esté abierta. La cola permite subir pistas y eliminar las que no están sonando. Se conserva la velocidad de audio preferida y la reanudación del servicio usa una posición guardada. Las posiciones cercanas al final se reinician.

## Libros y cómics

Ambos lectores tienen marcadores separados por documento. Se pueden guardar, abrir y eliminar sin duplicar posiciones. El progreso se guarda con retardo corto durante la lectura y se envía también al salir o al pasar a segundo plano. Las escrituras se serializan y los errores de guardado se muestran.

Los cómics añaden salto directo a número de página; mantienen manga, doble página, vertical, zoom y cuadrícula. La caché se adapta a tamaño de pantalla y modo de ajuste. Los libros añaden búsqueda en el capítulo actual con navegación entre coincidencias; conservan temas, tipografía, índice y progreso. La búsqueda global en todo el libro no está implementada.

## Validación y pruebas de dispositivo

```sh
gradle testDebugUnitTest lintDebug assembleDebug
```

Las pruebas cubren SRT/WebVTT, texto y codificaciones, frases superpuestas, desplazamientos manuales, calibración por referencias, deriva, saltos, límites de archivos, controles nativos y TextureView, parámetros de edición, marcadores, posición final y bucles A–B, además de las regresiones anteriores.

Para probar sin sustituir una instalación existente con otra firma:

```sh
gradle assembleDebug -PdevicePreview=true
```

Esto genera `com.parkingconcepts.mortimer.preview`, con nombre «MUGEN Preview» y almacenamiento independiente. El APK normal conserva `com.parkingconcepts.mortimer`. Las claves de depuración de diferentes equipos o ejecuciones de CI pueden ser distintas: Android no admite una actualización normal entre ellas. Una migración de firma requiere consentimiento y copia de seguridad; restaurar los datos privados no restaura por sí solo los permisos SAF concedidos a la instalación anterior.
