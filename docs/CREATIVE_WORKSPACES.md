# Studio y Audio Lab — versión 0.15.0

Estas herramientas trabajan sobre copias o referencias locales. Una exportación crea un archivo nuevo: no reemplaza el original ni publica contenido.

## Studio en la PWA

Abre **Studio** desde la navegación. Importa vídeos, selecciona un clip y ajusta entrada, salida, velocidad, volumen, giro, espejo y color. La línea de tiempo permite dividir, duplicar, mover y eliminar clips; Deshacer/Rehacer conserva hasta 50 estados durante la sesión.

El proyecto y los vídeos importados se guardan juntos en IndexedDB. **Save project** permite guardar inmediatamente. El estado de guardado debe confirmarse antes de cerrar la app; si el navegador rechaza el almacenamiento, el error permanece visible y se puede reintentar. Al volver a abrir, se intenta recuperar el proyecto y después su copia de respaldo. **New project** es una acción explícita: no recupera proyectos anteriores después de confirmarla.

**Export MP4** produce un montaje H.264/AAC, a 30 fps y 720p/1080p, con lienzo original, horizontal, vertical o cuadrado. Los clips sin audio reciben silencio para poder concatenarse. La velocidad de audio usa cambio de tempo conservando el tono. El render usa una instantánea del proyecto; los cambios posteriores requieren otra exportación. La cancelación termina el worker de exportación y conserva el proyecto.

La edición del navegador tiene límites explícitos: 32 fuentes/clips, 384 MiB de medios por proyecto y hasta 30 minutos de montaje. Estos límites evitan trabajos sin control, pero no garantizan que todos los dispositivos dispongan de memoria suficiente. Emplea fuentes más cortas o 720p si el navegador rechaza un render. El procesamiento es local y puede ser más lento que el tiempo real. Los formatos importables para vista previa dependen del decodificador del navegador; MP4 H.264/AAC es la opción de mayor compatibilidad.

La PWA no tiene todavía todas las funciones de Studio Android: el proyecto nativo, los subtítulos del montaje y algunas herramientas de línea de tiempo tienen un alcance diferente. No se deben intercambiar los archivos de proyecto entre plataformas.

## Audio Lab en la PWA

**Audio Lab** reúne el ecualizador de 10 bandas con filtros de paso alto/bajo, compresor, margen automático, limitador, delay y reverberación. El análisis muestra nivel de pico, RMS, reducción de ganancia y espectro. La comparación A/B permite escuchar el original frente al procesamiento; no modifica los ajustes guardados.

La cadena de procesamiento se comparte entre la reproducción HTML, el audio alternativo decodificado y el render WAV. Los ajustes parten de valores neutros y se validan antes de aplicarse. El margen automático reduce la ganancia cuando se acumulan realces; comparar a distinto volumen puede cambiar la percepción del resultado. Los valores de nivel son digitales; no representan el volumen acústico ni son una medida de sonoridad LUFS.

El banco de trabajo permite abrir un archivo de audio, escuchar el original, renderizar el resultado, preescucharlo y descargar una copia PCM WAV a 48 kHz. Si cambian los ajustes después de renderizar, debe regenerarse el resultado para que coincida con ellos. El banco de trabajo se limita a archivos de 64 MiB y cinco minutos; la decodificación y el render usan memoria. Las colas de reverberación/delay se incluyen con un límite de 12 segundos. Cancelar descarta el resultado pendiente; no altera el original.

Los presets se exportan con el formato `mugen-audio-lab`, versión 1, y ajustes internos versión 2. Las dos apps pueden intercambiar el subconjunto de efectos que comparten. La PWA avisa si un preset de Android incluye efectos que todavía no interpreta; los motores nativo y Web Audio no producen muestras idénticas. Conserva el preset original cuando hagas una conversión parcial.

La PWA admite grabación de micrófono con permiso del navegador, parada al salir del módulo y guardado de la toma original. Las tomas son locales y de sesión: guarda el original antes de cerrar. La afinación de voz y el sintetizador avanzado siguen siendo funciones específicas de Android.

## Mejoras de robustez en Android

Studio guarda un punto de control antes de abrir otro proyecto o salir del editor. Si el guardado falla, conserva el proyecto actual y permite **Save now**. Deshacer y rehacer vuelven a marcar el estado como pendiente de guardar.

Al seleccionar un destino MP4, se reserva el archivo renderizado y se bloquean cambios que pudieran sustituirlo mientras el selector está abierto. Una cancelación invalida callbacks tardíos del codificador. Durante la preparación del encoder se muestra progreso indeterminado en vez de una cifra inventada.

Audio Lab conserva las exportaciones WAV pendientes si se cancela el selector o falla la escritura, de modo que puedan guardarse de nuevo. La lectura de presets recupera las entradas válidas y el intercambio valida formato, tamaño y parámetros antes de cambiar los ajustes.

## Comprobaciones

Las comprobaciones de esta versión incluyen pruebas de modelo, tiempos y límites; pruebas de guardado/exportación nativas; y un flujo de navegador con medios sintéticos, exportación MP4 real, render WAV real, recuperación tras recargar y capturas de escritorio/móvil. Los resultados definitivos se registran en el PR y en sus artefactos de CI.

Quedan fuera de las pruebas automáticas el micrófono físico, los proveedores de archivos de cada fabricante, tarjetas SD/USB reales y la escucha crítica en hardware. Esas comprobaciones deben completarse antes de distribuir una versión firmada.
