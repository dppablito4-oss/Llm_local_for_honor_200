# Auditoría del repositorio — 17 de octubre de 2026

Estado revisado: commit `30668d6`; árbol limpio al comenzar. Informe basado en
lectura del código y verificaciones locales, sin modificar la aplicación.

## Dictamen

La base de chat local tiene persistencia Room, presupuesto de contexto con
tokenizer nativo, resúmenes acumulativos, memoria con alcance y pruebas reales
ya escritas. No está todo pendiente. Sin embargo, faltan integración RAG,
coordinación segura de difusión, automatización efectiva de calidad y validación
actual de las rutas de producción. No consideraría cerrado el producto.

La auditoría de julio contiene afirmaciones que ya no corresponden al código.
No debe usarse como lista actual de errores ni como certificación del motor.

## Verificación realizada

| Comprobación | Resultado |
|---|---|
| `:app:testDevDebugUnitTest` | 256 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `:app:testPlayDebugUnitTest` | 256 pruebas, 0 fallos, 0 errores, 0 omitidas |
| `:app:assembleDevDebug` | Correcto, con compilación nativa para las dos ABI configuradas |
| `:app:lintDevDebug` | FALLA: 1 error, 44 advertencias y 1 sugerencia |
| `:app:assembleDevBenchmark` | Correcto en 7 min 9 s; compilación nativa de ambas ABI y R8 |
| Alineación Debug APK | `zipalign -c -P 16 4` termina con código 0 |
| ELF arm64 RelWithDebInfo | Segmentos LOAD de `libllmhost.so` con alineación `0x4000` (16 KB) |
| Alineación Benchmark APK | `zipalign -c -P 16 4` termina con código 0 |
| Contrato JNI de difusión en Benchmark | FALLA: el DEX no contiene el constructor requerido por JNI (A13) |
| Play Release firmado | No verificado; el entorno informa firma release sin configurar |
| Instrumentación en teléfono | No ejecutada en esta revisión |

Los dos conjuntos JVM ejecutan la suite sobre dos variantes; no equivalen a
512 casos funcionales diferentes. Parte de la compilación reutiliza resultados
incrementales. No se realizó una compilación limpia de todos los terceros.
Hay un dispositivo ADB conectado; no se instaló ni alteró su aplicación.

El inventario propio contiene 169 archivos bajo Java/C++ principal, excluyendo
`third_party`, y 72 archivos bajo los directorios de pruebas. Se revisaron las
rutas principales y sus conexiones; no se afirma una revisión línea por línea
de todos los motores vendorizados, una auditoría del servidor cloud ni una
prueba de penetración.

## Hallazgos prioritarios

### A01 — Alta: destrucción del motor de imágenes durante una generación

Evidencia: `app/src/main/java/com/prismai/llmhost/service/InferenceService.kt:1701`,
`diffusion/DiffusionManager.kt:122`, `diffusion/NativeDiffusionBridge.kt:79` y
`app/src/main/cpp/diffusion/DiffusionEngine.cpp:133,167,255`.

`generateImage()` usa un mutex Kotlin, pero `unload()` no lo adquiere.
`onDestroy()` llama a `unload()` directamente. En C++, `generate()` copia
`sd_ctx` y libera el mutex antes de usarlo; `unload()` puede liberar ese mismo
contexto mientras `generate_image()` sigue ejecutándose. El puente tampoco
protege la vida del handle entre una llamada JNI y `close()`.

Consecuencia: posible uso de memoria liberada y cierre nativo al destruir el
servicio durante una imagen. Es un riesgo demostrado por la sincronización del
código; no se provocó un crash en el dispositivo.

Falta: cancelar difusión, esperar realmente su finalización y después liberar
el contexto fuera del hilo principal. Añadir una prueba de ciclo de vida con
generación activa y protección compartida de la vida del handle.

### A02 — Alta: `/image` evita la coordinación y Stop no cancela difusión

Evidencia: `service/InferenceService.kt:692,868,881,1090`.

El comando retorna hacia `generateImageDirect()` antes de
`cancelRollingSummaryAndJoin()` y `operationMutex.withLock`. No registra el
trabajo como `generationJob`. El botón de cancelar recorre la ruta de texto;
no se encontró ninguna llamada de servicio a `diffusionManager.cancel()`.

Una imagen puede coincidir con texto o un resumen; un segundo trabajo puede
alterar `_isGenerating` y retirar la notificación del primero. El mutex interno
de difusión serializa imágenes, pero no coordina texto, resúmenes, foreground
ni la destrucción del servicio.

Falta: una sesión de operación para texto e imagen, propietario de estado y
notificación, cancelación accesible y exclusión de trabajos pesados según RAM.
Probar Stop, dos solicitudes, imagen durante texto y cambio/destrucción del
servicio.

### A03 — Alta para calidad: CI está en una ubicación inactiva

Evidencia: `PrismLocal/.github/workflows/android-ci.yml` respecto de la raíz Git
`E:/Llm_local_for_honor_200`. No hay `.github/workflows` en esa raíz.

GitHub Actions descubre workflows en `.github/workflows` de la raíz del
repositorio. Este workflow quedó anidado al incorporar PrismLocal. Además, sus
comandos usan `./gradlew` sin configurar el directorio del subproyecto.

Falta: ubicar el workflow en la raíz y definir `working-directory: PrismLocal`
para los comandos pertinentes. Integrar lint y las pruebas C++ portables; las
actuales tareas Gradle no ejecutan `app/src/test/cpp/CMakeLists.txt`.

### A04 — Media: lint falla en un recurso de API incompatible

Evidencia: `app/src/main/res/values/styles.xml:4` y
`app/build/reports/lint-results-devDebug.html`.

`android:windowLightNavigationBar` requiere API 27 y `minSdk` es 26. El fallo
se reprodujo ejecutando lint. Mover la propiedad a recursos `values-v27`, con
un estilo base compatible. No basta con que el APK Debug compile.

También existe una discrepancia de soporte: Gradle declara `minSdk=26`, pero
pasa `ANDROID_PLATFORM=android-29` a CMake. Falta alinear el mínimo nativo con
el mínimo Android o verificar explícitamente API 26–28 antes de anunciarlas.
Esto último no demuestra por sí solo un crash en esas versiones.

### A05 — Alta funcional: documentos no alimentan el chat normal

Evidencia: `generation/PromptBuilder.kt:60,140` y
`generation/GenerationOrchestrator.kt:195`.

Existe `buildRagContext()`, pero la búsqueda de referencias no encuentra un
consumidor en producción. El chat normal llama a `prepareContext()` sin
`ragContext`; se usa el valor vacío por defecto. Las herramientas de documentos
pueden buscar explícitamente, pero eso no completa el RAG del chat normal.

Falta: seleccionar colecciones por chat, recuperar dentro de ese alcance e
inyectar fragmentos con presupuesto y procedencia. El esquema de vectores tiene
`documentId`, pero carece de colección y asociación chat–colección; conectarlo
globalmente ahora introduciría mezcla de documentos entre conversaciones.

### A06 — Media: falta una estrategia estable de embeddings e ingesta

Evidencia: `service/InferenceService.kt:341`, `app/src/main/cpp/Engine.cpp:2031`
y `storage/VectorStore.kt`.

La ruta actual llama a `engine.encode()`, que usa el modelo activo de chat y
`llama_encode()`. `LightweightEmbeddingEngine` sigue presente, pero no es el
codificador conectado al servicio: la acusación de julio sobre hash embeddings
activos está desactualizada.

Falta validar extracción/pooling en los modelos soportados y guardar identidad,
versión, dimensión y normalización del codificador. Cambiar de modelo puede
producir vectores de otro espacio o dimensión sin reconstruir el índice.
No se encontraron pruebas específicas de ingesta/recuperación con esos modelos.
No se afirma que todos fallen: falta la evidencia de compatibilidad y calidad.

`RagManager` acumula todos los vectores del documento antes de insertarlos;
reingestar el mismo `documentId` genera nuevos IDs y añade fragmentos sin
sustituir los anteriores. Falta ingesta por lotes acotados, sustitución
transaccional, cancelación y pruebas de reingesta. La búsqueda ya retiene solo
top-K en memoria, aunque sigue recorriendo linealmente los registros. Medir
latencia y RAM antes de decidir si hace falta ANN.

### A07 — Alta de estabilidad: presión crítica no libera los modelos

Evidencia: `service/InferenceService.kt:585,593` y
`app/src/main/cpp/Engine.cpp:2172`.

La ruta de texto marca presión/cancelación y guarda transcript en el monitor,
pero no descarga pesos ni libera la caché. La ruta de difusión tampoco está
integrada con este gobernador. Un LLM y un modelo de imágenes pueden permanecer
cargados a la vez.

Falta una política de presupuesto conjunto, parada y liberación bajo presión
crítica, con recuperación posterior. Guardar el transcript es útil, pero no
resuelve la retención de memoria. Medir memoria nativa/PSS, además del heap JVM.

### A08 — Alta potencial: carrera residual en el handle del LLM

Evidencia: `bridge/NativeLlmBridge.kt:188,232,277` y
`app/src/main/cpp/llmhost_jni.cpp:551`.

`setThreadCount()` y `setMemoryPressure()` comprueban `isDestroyed` y llaman JNI
sin compartir el bloqueo de `destroySafely()`. Otro hilo puede destruir el
objeto entre la comprobación y su uso. El guard C++ contra `nullptr` no valida
un puntero no nulo que ya se liberó. El comentario que declara segura esta
secuencia no proporciona esa garantía.

Falta una estrategia común de propiedad del handle para destrucción y llamadas
concurrentes, preservando una cancelación que no espere al decode. No se
reprodujo un uso de memoria liberada durante esta revisión.

### A09 — Media: trabajos y descargas dinámicas no sobreviven al proceso

Evidencia: `BackgroundAgentManager.kt:91`, `HuggingFaceModelCatalog.kt:459` y
`HuggingFaceDownloadWorker.kt:93`.

La cola de agente vive en `MutableStateFlow`, sin persistencia. Las descargas
personalizadas guardan el catálogo en un mapa en memoria, mientras WorkManager
persiste solo `entry_id`: si reinicia el proceso, `find(entryId)` ya no puede
reconstruir una entrada personalizada. Además, el ID truncado a 40 caracteres
puede colisionar entre archivos del mismo repositorio.

Falta persistir cola y especificación completa de descarga, usar IDs resistentes
a colisiones y probar reinicio/recuperación con una descarga personalizada.

### A10 — Media: acciones de producto incompletas

- `ui/chat/MessageItem.kt:94`: “Report saved locally” solo ejecuta un Toast;
  no persiste ni envía un reporte. Implementar guardado real o corregir el texto.
- La UI de mensajes revisada ofrece copiar/reportar; faltan edición del mensaje,
  borrado individual y regeneración con manejo coherente del historial/resumen.
- `ui/rag/DocumentBrowser.kt`: ingesta mediante texto pegado. Faltan importación
  documental, PDF textual por páginas, OCR opcional y citas verificables.
- `diffusion/DiffusionEngine.cpp:67`: `on_gpu` se registra en logs, pero no se
  usa para configurar el backend. No hay garantía de que `onGpu=false` fuerce
  CPU. Falta selección efectiva y telemetría del backend usado.
- La elección de difusión usa coincidencias `sd`/`diffusion`/`turbo` en el nombre,
  mientras el gestor de modelos de texto lista los mismos modelos instalados.
  Falta persistir y consumir un tipo de modelo, y parámetros por arquitectura:
  SDXS, Turbo y SD 1.5 no deben asumir los mismos pasos y CFG.
- La creación manual de memoria en el servicio no recibe chat ni alcance y
  termina creando memoria GLOBAL. La infraestructura sí soporta CHAT, pero
  falta ofrecer esa elección explícita al crear una memoria desde la UI.

### A11 — Media de rendimiento: IO bloqueante y renderizado creciente

Evidencia: `storage/RoomMemoryStore.kt:36`,
`service/InferenceService.kt:1288`, `ui/CodeBlockRendering.kt:730,788`.

Las APIs de memoria envuelven Room con `runBlocking(Dispatchers.IO)`; el hilo
llamante sigue bloqueado, incluidos refrescos directos durante `onCreate()` y
acciones UI. Convertir las APIs a suspend/Flow o mantener una caché observable.

El markdown se vuelve a analizar completo cada vez que cambia el texto y las
imágenes se decodifican sincrónicamente durante composición. Hay riesgo de jank
con respuestas largas y archivos grandes; no se midieron FPS. Falta carga de
imagen fuera de main con tamaño acotado y medir parsing/scroll en el Honor 200.

### A12 — Alta de integridad: el puente aún puede perder texto por backpressure

Evidencia: `bridge/NativeLlmBridge.kt:294` y el consumo de `chunk.text` en
`generation/GenerationOrchestrator.kt`.

`emitChunk()` reintenta durante aproximadamente 100 ms y descarta el fragmento
de datos si el canal continúa lleno. En ese momento los tokens ya se drenaron
del buffer nativo. Se conserva el terminal, pero no se reconstruye el fragmento
perdido: una generación puede terminar normalmente con texto incompleto.

Falta entrega sin pérdida, acumulación acotada o reconciliación con una copia
autoritativa antes de persistir. Probar un consumidor bloqueado y verificar
igualdad exacta del texto generado y del almacenado. El descarte está explícito
en código; no se forzó esa condición durante las pruebas ejecutadas.

### A13 — Alta confirmada en APK: R8 elimina el constructor usado por difusión JNI

Evidencia: `app/src/main/cpp/diffusion/diffusion_jni.cpp:97`,
`app/proguard-rules.pro`, `app/build/outputs/mapping/devBenchmark/mapping.txt`
y `app/build/outputs/apk/dev/benchmark/app-dev-benchmark.apk`.

El código JNI busca `DiffusionNativeResult` y su constructor de firma
`(ZIIILjava/lang/String;[BLjava/lang/String;F)V`. Después de compilar Benchmark,
se inspeccionaron las tablas de métodos de todos los DEX del APK: la clase
conserva `equals`, `hashCode` y `toString`, pero no tiene `<init>`. La firma que
requiere JNI no existe en el artefacto minificado. El mapping también carece
del constructor. Las reglas por defecto preservan el nombre de clases usadas
en firmas nativas, pero eso no conserva este constructor invocado desde C++.

Consecuencia: `GetMethodID` no puede resolver el constructor al devolver el
resultado de generación de imagen. La compilación correcta no demuestra el
funcionamiento de la difusión en la variante minificada. No se ejecutó el APK
en el teléfono para observar la excepción: la incompatibilidad binaria quedó
confirmada inspeccionando el artefacto.

Falta conservar explícitamente clase y constructor usados por JNI, y probar la
devolución de resultado en una variante minificada. Verificar el mismo contrato
en Play Release, sin asumir que Debug cubre R8.

## Qué ya mejoró frente a julio

| Afirmación anterior | Evidencia actual |
|---|---|
| Manifest specialUse y código dataSync incompatibles | El servicio usa `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` |
| Falta abort callback | `Engine.cpp:1084` lo instala y el guard de limpieza lo retira |
| Errores nativos no llegan a Kotlin | `NativeDrainResult.errorCode` está enlazado por JNI |
| RAG recupera con umbral cero | `RagManager.DEFAULT_MIN_RAG_SCORE = 0.35f` |
| Búsqueda vectorial materializa todo para ordenar | Cursor y heap top-K acotan los candidatos retenidos |
| Ruta directa evita confirmaciones | Pasa por `handleToolCall()`, que valida y despacha según riesgo |
| No hay persistencia de confirmación | El router llama `confirmation.persist(...)` |
| READ_SMS/CONTACTS/CALENDAR activos | No están declarados en el manifest principal actual |
| Título del modelo no interactivo | `ChatTopBar` ya tiene menú y callback de cambio |
| Primer arranque dice Reconnecting | El compositor usa “Starting Prism Local...” |
| Memoria y resumen totalmente pendientes | Hay RoomMemoryStore, alcance GLOBAL/CHAT y resumen acumulativo |

Estas observaciones verifican presencia y conexión en código, no certifican
todos los casos de carrera, recuperación o calidad en el teléfono. La ausencia
de FileProvider tampoco implica un crash general: las exportaciones existentes
usan SAF/CreateDocument y las imágenes `file://` se muestran dentro de la app.
Si se añade compartir con otras apps, habrá que implementar content URIs.

## Orden recomendado y criterios de cierre

1. **Estabilidad:** A01, A02, A08, A12 y A13. Cerrar con pruebas de Stop, destrucción,
   concurrencia, repetición y cambio de modelo en imagen/texto.
2. **Calidad automatizada:** activar CI en la raíz, corregir lint e incorporar
   las pruebas C++ portables. Verificar Debug, Benchmark y Play Release.
3. **Memoria:** presupuesto LLM+difusión+KV, descarga controlada bajo presión
   crítica y mediciones sostenidas en el Honor 200.
4. **RAG útil:** colección por chat, codificador estable/versionado, reingesta
   segura, conexión a ContextBuilder y consultas de aceptación en español.
5. **Documentos:** PDF paginado, citas y modo estricto con casos donde no existe
   evidencia. Evaluar OCR después de la ruta textual.
6. **Recuperación y UX:** cola/descargas persistentes, reportes reales,
   editar/regenerar y renderizado fluido.
7. **Entrega:** firma/configuración externa, prueba de APK minificado en teléfono,
   revisión de alineación 16 KB y documentación coherente con evidencias del
   commit. No basta con números de rendimiento en README sin los resultados,
   modelo/hash, ajustes y condiciones de la medición.

## Límites y validaciones pendientes

- Falta ejecutar instrumentación actual, especialmente difusión y ciclo de vida;
  existen pruebas de inferencia real, Room, cambios de modelo y resumen.
- El contrato `DiffusionNativeResult` se comprobó en Benchmark y falla (A13).
  Falta verificar su reparación en el APK y ejecutar el caso en el teléfono.
- No se inspeccionaron configuración remota, RLS ni servidor Prismatix. El
  cliente incluye cifrado de tokens y bloqueo de redirects con credenciales.
  `ChatBackendManager.signOut()` borra la sesión local; no hay revocación remota
  implementada en la interfaz del cliente. Definir el contrato de cierre de
  sesión antes de anunciar revocación de sesiones.
- `CLAUDE.md`, `PROJECT_CONTEXT.md`, planes y README contienen comandos o estados
  antiguos. Actualizarlos con tareas calificadas por flavor y marcar la
  auditoría de julio como histórica. No sustituir evidencia actual por fechas
  de documentos anteriores.
- No se verificaron CVE actuales ni se recomienda actualizar dependencias en
  bloque basándose únicamente en las advertencias de nuevas versiones de lint.

Cambio realizado por esta revisión: únicamente este informe. No se aplicaron
correcciones al código, no se hicieron commits y no se publicó nada.

## Seguimiento posterior — implementación y pruebas del bloque 1

La sección anterior describe la auditoría inicial. Posteriormente se aplicaron
las correcciones autorizadas del bloque 1; el estado y la evidencia están en
[plam-implementation.md](plam-implementation.md). Sus cinco pasos están cerrados:
JNI/R8, vida de motores, entrega completa de texto, coordinación/Stop de imágenes
y lint/CI. Se probaron en Honor 200 / Android 16 con SDXS sobre CPU y texto GGUF
real. No equivale a soporte validado de GPU, RAG, VLM ni de todos los modelos.

El gate local completo pasa: 512 tests JVM, lint sin errores en ambos flavors,
6 ejecutables C++ en teléfono, Debug/Benchmark/Play Release, contratos JNI del
DEX final y alineación APK/ELF de 16 KB. Imágenes, cancelación CLIP/UNet,
reemplazo, recuperación de texto y ausencia de follow-up tras Stop pasan en el
APK final. CI se trasladó a la raíz Git y su YAML se verificó; no se ejecutó
Actions remotamente ni se hicieron commits/publicaciones.

### A14 — Media: un backup JSON tardío puede sobrevivir a deleteChat

Durante las pruebas, el chat temporal se eliminó de Room, pero quedó su JSON.
La comparación de los 4 chats y 24 mensajes originales confirmó que no hubo
cambios en sus conversaciones. El archivo residual se identificó por los
marcadores exclusivos del fixture y se retiró; los 9 archivos del respaldo
reciente quedaron restaurados byte a byte.

`chat/ChatManager.kt` elimina el transcript antes de coordinar las escrituras
con `ioMutex`; `persistSnapshotJsonBackup()` puede escribir un snapshot antiguo
después. Falta serializar borrado/escrituras y descartar snapshots de chats ya
eliminados. No se observó resurrección del chat en Room. Este punto queda fuera
del bloque 1, pendiente en la fase de persistencia y recuperación.
