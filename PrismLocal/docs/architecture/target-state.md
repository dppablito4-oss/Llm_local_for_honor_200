# PrismLocal: arquitectura objetivo incremental

Fecha: 2026-09-19

## Objetivo

Construir un chat local persistente, multimodelo y preparado para documentos sin
mantener mas de un LLM generativo pesado en RAM. La base actual se evoluciona de
forma incremental; no se reemplazan `llama.cpp`, el streaming ni la gestion de
modelos que ya funcionan.

## Invariantes

1. Los chats pertenecen a la aplicacion, no a un modelo.
2. Solo un LLM generativo pesado puede estar cargado.
3. El historial textual y la base de datos son la fuente de verdad; el KV cache
   es descartable.
4. Cada modelo reconstruye y tokeniza el contexto con su propio tokenizer.
5. Memoria de chat y documentos asociados nunca contaminan otro chat.
6. Ninguna migracion elimina los JSON o SQLite anteriores hasta verificar
   conteos y contenido.
7. PDF, embeddings y OCR se procesan por pagina/lote con memoria acotada.

## Componentes objetivo

```text
Compose UI
   |
InferenceService (fachada y ciclo Android)
   |
coordinacion unica: cancelar -> descargar -> cargar -> generar
   |
ContextBuilder
   |-- sistema
   |-- memoria global
   |-- memoria del chat
   |-- fragmentos RAG filtrados
   |-- resumen acumulativo
   |-- mensajes recientes
   `-- pregunta actual
   |
NativeLlmBridge -> llama.cpp

Room: PrismDatabase
   |-- chats / messages
   |-- memories
   |-- documents / chunks / collections
   `-- benchmark runs
```

Los GGUF continuan como archivos versionados administrados por
`ModelStorageManager`. Room solo conserva metadatos consultables cuando sea
necesario.

## Secuencia de entrega

### 0. Contrato y pruebas de regresion

Estado: completado el 2026-09-19.

- mantener este documento y `current-state.md` sincronizados con el codigo;
- probar persistencia, aislamiento de chats y continuidad tras modelo A -> B;
- capturar una linea base de rendimiento antes de cada cambio de datos.

### 1. Room para conversaciones

Estado: completado el 2026-09-19. El esquema, la importacion idempotente, la
escritura, el corte de lectura y su verificacion en el Honor 200 estan
completados. JSON permanece temporalmente como respaldo de recuperacion.

Introducir `PrismDatabase`, `ChatEntity`, `MessageEntity` y sus DAO. Implementar
una importacion idempotente desde `chat_index.json` y `chats/*.json`:

1. abrir Room y ejecutar la importacion en transaccion;
2. comparar cantidad e identificadores;
3. marcar la migracion completada;
4. conservar los archivos originales como respaldo hasta una version posterior.

La escritura dual reemplaza instantaneas completas por chat y elimina en Room
las sesiones ausentes. Las operaciones se verificaron el 2026-09-19 en el
dispositivo con creación, edicion, limpieza y eliminacion, ademas del arranque
real de la aplicacion y la reconciliacion inicial. Tambien se retiro
`chat_index.json` durante una prueba: la aplicacion recupero tres chats y seis
mensajes exclusivamente desde Room y regenero despues el respaldo JSON.

Memoria y vectores permanecen temporalmente en sus bases existentes para no
mezclar tres migraciones de riesgo en un solo cambio.

### 2. ContextBuilder y presupuesto observable (implementado 2026-09-19)

Crear `PreparedContext` con las secciones incluidas, tokens por seccion,
fragmentos descartados y modelo/tokenizer usado. El orden de recorte sera:

1. reservar salida y margen del template;
2. conservar sistema y pregunta actual;
3. seleccionar memoria/RAG con limites propios;
4. incluir resumen;
5. completar con mensajes recientes desde el mas nuevo.

Perfiles iniciales del Honor 200:

- rapido: 2048 de contexto;
- equilibrado y RAG: 4096;
- 8192: experimental por modelo;
- 16K o mas: aplazado hasta medir RAM, TTFT y estabilidad.

`PreparedContext` ya registra secciones, reservas, estimaciones y descartes. El
chat normal cuenta el prompt formateado con el tokenizer nativo activo, reajusta
el historial si excede la ventana y conserva la estimacion conservadora como
respaldo cuando no hay modelo. La implementacion se verifico con pruebas JVM y
en el Honor 200: el GGUF de humo conto 16 tokens para un chat corto y 160 para
el chat ampliado, y luego genero por la ruta estructurada sin error.

Queda para fases posteriores alimentar las secciones de resumen y RAG, y migrar
las rutas heredadas de agentes/benchmarks al mismo constructor.

### 3. Resumen acumulativo

Al superar un umbral, resumir solo el bloque antiguo, guardar el resultado en el
chat y avanzar `summaryUntilMessageId`. Los mensajes originales nunca se borran.
El prompt usa resumen mas mensajes recientes.

### 4. Memoria aislada

Migrar memoria a Room despues de estabilizar conversaciones. Definir alcance
`GLOBAL` o `CHAT`, filtrar antes de puntuar y mejorar normalizacion en español.
La extraccion automatica se introduce primero como sugerencia confirmable para
evitar guardar alucinaciones de modelos pequeños.

### 5. RAG integrado

Agregar documento, coleccion, fragmento y relaciones chat-coleccion. Conectar la
recuperacion al `ContextBuilder`; nunca inyectar documentos no asociados al
chat. Mantener busqueda coseno lineal mientras las colecciones sean pequeñas y
medir antes de introducir un indice ANN.

### 6. PDF, OCR y citas

- PDF textual: extraer e indexar pagina por pagina;
- PDF escaneado: OCR opcional por pagina;
- guardar pagina/seccion para citar;
- modo estricto: responder que no hay evidencia cuando los fragmentos no bastan;
- imagenes conceptuales: fase multimodal posterior, independiente de OCR.

### 7. Presupuesto de memoria y metricas

Registrar prompt tokens, velocidad de prompt, TTFT, tokens de salida, bateria,
temperatura y pico de memoria. El presupuesto debe considerar pesos, KV cache,
embedding, lote de ingesta y buffers antes de aceptar un contexto o documento.

## Gates de aceptacion

- reinicio del proceso conserva chats y mensajes;
- dos chats no comparten historial, memoria privada ni colecciones;
- cambiar modelo A -> B conserva el chat y reconstruye el contexto;
- 1000 mensajes no producen un prompt fuera del limite;
- un PDF de 50 paginas se indexa con memoria acotada y se puede cancelar;
- las citas corresponden al documento y pagina recuperados;
- el modo estricto no completa datos ausentes;
- la presion critica guarda estado y cancela trabajo pesado de forma segura.

## Fuera de este bloque

GPU/Vulkan, NPU/Hexagon y modelos de vision se investigan como pistas separadas.
No bloquean chats persistentes, memoria, RAG textual ni PDF. Los agentes
existentes se mantienen compatibles, pero no se amplian hasta que todos consuman
el mismo `ContextBuilder`.
