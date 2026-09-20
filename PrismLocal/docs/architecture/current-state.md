# PrismLocal: estado arquitectonico actual

Fecha de auditoria: 2026-09-19  
Commit base observado: `b5780ef31ce035563f644c2be5c36aea423ede99`

Este documento describe el codigo que existe hoy. No es la arquitectura
objetivo ni presupone que una funcion expuesta en la interfaz ya forme parte del
flujo normal de generacion.

## Resumen

PrismLocal ya tiene una base valida para chat local multimodelo:

- un solo `NativeLlmBridge` y un solo modelo generativo pesado residente;
- cambio de modelo serializado y cancelacion antes de descargar el modelo;
- mensajes estructurados que el modelo activo vuelve a tokenizar;
- chats independientes del modelo, leidos y persistidos en Room con una copia
  JSON de recuperacion;
- memoria y vectores persistidos en dos bases SQLite separadas;
- streaming, cancelacion, metricas y reaccion a presion de memoria.

Los huecos principales son el resumen acumulativo, el aislamiento de memoria por
chat y la conexion efectiva de RAG con la generacion normal.

## Flujo de inferencia

`InferenceService` es la raiz de composicion y fachada para la UI. La operacion
de generacion, cancelacion y cambio de modelo se serializa con
`operationMutex`. Las responsabilidades principales ya estan separadas en:

- `ModelManager`: carga, descarga y validacion de modelos;
- `GenerationOrchestrator`: ejecucion y telemetria de una generacion;
- `ContextBuilder` y `PromptBuilder`: presupuesto, seleccion y formato del contexto;
- `ChatManager`: estado y operaciones de chats;
- `TranscriptStore`: lectura y escritura de transcripciones;
- `MemoryGovernor`: notificacion de presion de memoria.

No se necesita introducir otro coordinador en paralelo. Si se extrae un
`InferenceCoordinator`, debe hacerse moviendo gradualmente la maquina de estado
actual y conservando una sola autoridad.

## Chats y persistencia

El backend activo de chats se guarda en almacenamiento privado:

- `files/chat_index.json`: indice de sesiones;
- `files/chats/<chatId>.json`: mensajes de cada chat;
- `SharedPreferences`: identificador del chat activo.

Room crea `databases/prismlocal.db` con las tablas `chats`, `messages` y
`migration_state`. Si la base esta vacia, una importacion idempotente copia y
verifica los JSON dentro de una sola transaccion. Desde el corte del
2026-09-19, Room es la fuente de lectura y escritura para altas, renombrados,
mensajes, ediciones, limpiezas y eliminaciones. Los JSON se regeneran en segundo
plano como respaldo recuperable y ya no gobiernan el arranque.

Las escrituras usan archivo temporal y promocion atomica cuando el sistema de
archivos lo permite. El historial sobrevive a un cambio de modelo porque el
modelo no es propietario del chat. `ChatSession.modelId` representa el ultimo
modelo asociado, no una dependencia que bloquee la sesion.

Limitaciones actuales:

- `TranscriptMessage` no expone en el dominio fecha, modelo autor, estado ni
  conteo de tokens, aunque su entidad Room si conserva el `chatId`;
- `ChatSession` no almacena resumen acumulativo ni el limite ya resumido;
- el respaldo JSON es asincrono y no debe tratarse como fuente de verdad.

## Cambio de modelo y contexto nativo

Al cambiar de modelo se cancela la generacion activa, se descarga el contexto
nativo anterior y se carga el nuevo GGUF. El KV cache no se persiste ni se
comparte entre modelos. En la siguiente pregunta, el historial textual se
reconstruye y se procesa con el chat template y tokenizer del modelo activo.

El cache de prefijo nativo es una optimizacion temporal e incluye la identidad
del modelo y de su configuracion. No es la fuente de verdad de la conversacion.

## Construccion del contexto

Para chat normal, `GenerationOrchestrator` obtiene memoria y llama a
`PromptBuilder.prepareContext`. `ContextBuilder` reserva salida y margen de
template, conserva sistema y pregunta actual, limita memoria/RAG/resumen por
seccion y llena el resto con los mensajes recientes.

El resultado `PreparedContext` registra mensajes enviados, estimacion por
seccion, elementos incluidos/descartados, reservas y fuente del conteo. Con un
modelo cargado, JNI aplica su chat template y el tokenizer real antes de iniciar
la generacion. Si el conteo excede la ventana, Kotlin vuelve a empaquetar con un
presupuesto menor; si ni el contexto fijo cabe, rechaza la solicitud en vez de
permitir que C++ elimine silenciosamente el inicio del prompt. Sin modelo, queda
la estimacion conservadora como respaldo. El ultimo resultado tambien se expone
como `StateFlow` de diagnostico.

Limitaciones actuales:

- los tokens por seccion son estimados; el total final si usa el tokenizer nativo;
- agentes, benchmarks y continuaciones aun usan sus rutas de prompt heredadas;
- RAG y resumen tienen espacio reservado en el constructor, pero todavia no se
  alimentan en el chat normal;
- no hay resumen acumulativo de conversaciones largas;
- al agotarse el presupuesto, los mensajes mas antiguos dejan de enviarse al
  modelo aunque siguen guardados en Room.

## Memoria

`SqlMemoryStore` utiliza `prism_memory.db` mediante `SQLiteOpenHelper`. Los
hechos incluyen `sourceChatId`, confianza y estadisticas de acceso.

La recuperacion normal obtiene todos los hechos activos y luego usa coincidencia
lexica. `sourceChatId` no se aplica como filtro, por lo que actualmente una
memoria originada en un chat puede aparecer en otro. `MemoryExtractor` existe,
pero no esta conectado como paso automatico despues de cada respuesta.

## RAG

`VectorStore` utiliza `prism_vector_store.db`. Guarda texto y embeddings como
BLOB y calcula similitud coseno recorriendo las filas, manteniendo solo el top-k
en memoria. Este enfoque es aceptable inicialmente para algunos miles de
fragmentos.

`RagManager` puede fragmentar, codificar, almacenar y consultar texto. Sin
embargo:

- `PromptBuilder.buildRagContext` no es llamado por la generacion normal;
- no existen entidades de documento, coleccion o relacion chat-documento;
- no se conserva titulo, pagina, seccion, URI, MIME, hash ni estado de indexado;
- la ingesta crea todos los fragmentos y embeddings antes de insertarlos;
- `engine.encode()` comparte el motor con el LLM generativo;
- no hay citas ni modo estricto de documentos.

Por tanto, el RAG actual es infraestructura de almacenamiento/consulta y
herramientas, no un RAG integrado al chat.

## Adjuntos y PDF

`AttachmentTextExtractor` admite texto plano y MHTML con limites de bytes y
caracteres. Las imagenes y formatos no soportados solo aportan metadatos. Un PDF
no se extrae ni se indexa actualmente.

Un PDF textual futuro debe procesarse por pagina y lotes. Un PDF escaneado
requiere un camino OCR separado. Una imagen de mapa conceptual requiere un
modelo multimodal; OCR no sustituye el analisis visual.

## Metricas y memoria

Los benchmarks se guardan en `benchmark_runs.json`. Incluyen velocidad,
configuracion, backend, tiempos y razon terminal, pero no relacionan de forma
estable chat/mensaje, bateria inicial/final ni pico de RAM.

`MemoryGovernor` informa la presion a C++ y guarda la transcripcion en estado
critico. Todavia no hay un presupuesto global que contabilice por separado
pesos, KV cache, embeddings, buffers de RAG y UI.

## Riesgos que deben corregirse primero

1. Mezcla de memoria entre chats.
2. RAG visible pero no conectado a respuestas normales.
3. Conversaciones largas sin resumen acumulativo.
4. Unificar las rutas heredadas de agentes con el `ContextBuilder`.
5. Unificar las bases separadas de memoria y vectores solo despues de estabilizar
   el constructor de contexto y RAG.
6. PDF e imagenes presentados como adjuntos aunque su contenido no se procesa.
