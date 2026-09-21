# 🗺️ Plan Secuencial de Desarrollo: PrismLocal en Honor 200

**Dispositivo Objetivo:** Honor 200 (Qualcomm Snapdragon 7 Gen 3 · 12 GB RAM · 256 GB UFS 3.1 · MagicOS 10 / Android 16)  
**Motor de Inferencia:** `llama.cpp` vía JNI protegido en C++20  
**Fecha de Actualización:** 21 de septiembre de 2026  
**Estado:** Fase 3 completada y validada en el Honor 200. Próxima fase: aislamiento estricto de memoria en Room.

---

## 1. Estado Actual: Hitos Consolidados

| Fase | Componente | Estado | Verificación |
|---|---|---|---|
| **Fase 0** | **Base Operativa e Inferencia en Snapdragon 7 Gen 3** | ✅ COMPLETADO | Modelos probados (Qwen 2.5 0.5B a 3B, Llama 3.2 1B/3B, DeepSeek-R1). 4 núcleos Cortex-A715 asignados. |
| **Fase 1** | **Persistencia Robusta en Room SQLite** | ✅ COMPLETADO | `PrismDatabase` con `chats` y `messages`. Importación y respaldo JSON idempotente. Supervivencia de chats tras cambio de modelo. |
| **Fase 2** | **Conteo de Tokens Nativo y Presupuesto Dinámico** | ✅ COMPLETADO | `ContextBuilder` y `PreparedContext` con tokenizer real del GGUF activo vía JNI. Modo Thinking (`<think>`) integrado. |
| **Fase 3** | **Resumen Acumulativo de Conversaciones Largas** | ✅ COMPLETADO | Qwen3 real en Honor 200: 13 mensajes sintetizados, `EOF`, resumen persistido en Room y 22 mensajes originales conservados. |

---

## 2. Invariantes Arquitectónicos del Sistema

1. **Un solo LLM pesado en memoria RAM:** Nunca se mantiene más de un modelo generativo cargado a la vez para proteger los 12 GB de RAM.
2. **Los chats pertenecen al usuario y a Room:** El modelo no es dueño de la conversación; cambiar de modelo conserva intacto el historial.
3. **El KV cache es efímero; el texto estructurado es la fuente de verdad:** Todo contexto se reconstruye con el chat template y el tokenizer del modelo activo.
4. **Aislamiento estricto de privacidad:** La memoria y los documentos de un chat jamás contaminan o se filtran a otra sesión de chat.
5. **Presupuesto preventivo de memoria:** No se acepta un prompt ni una ingesta si excede los límites seguros del sistema operativo.

---

## 3. Secuencia de Fases Pendientes (Roadmap Paso a Paso)

```text
FASE 3: Resumen Acumulativo (Rolling Summary)
   │
   ▼
FASE 4: Aislamiento Estricto de Memoria (Scope Global vs Chat en Room)
   │
   ▼
FASE 5: RAG Documental Integrado al Flujo Normal de Chat
   │
   ▼
FASE 6: Ingesta Real de PDFs y Documentos Estructurados
   │
   ▼
FASE 7: Gobernador Global de Memoria y Telemetría Completa
```

---

### 📌 Fase 3: Resumen Acumulativo de Conversaciones Largas (*Rolling Summary*)
> **Objetivo:** Permitir conversaciones ilimitadas sin perder el contexto inicial cuando el historial supere la ventana de tokens (2048 / 4096).

- [x] **3.1 Modelo de Dominio y Sincronización con Room:**
  - Exponer `summary: String?` y `summaryUntilMessageId: Long?` en el modelo de dominio `ChatSession.kt`.
  - Conectar el mapeo bidireccional entre `ChatEntity` (que ya contiene ambos campos) y `ChatSession`.
  - Agregar método `updateSummary(chatId, summary, untilMessageId)` en `ConversationDao.kt`.

- [x] **3.2 Inyección de Resumen en `GenerationOrchestrator`:**
  - Extraer el `summary` del chat activo antes de la inferencia.
  - Pasar `summaryContext` al método `ContextBuilder.prepare(...)` (que ya reserva hasta 512 tokens para esta sección).
  - Asegurar que los mensajes anteriores a `summaryUntilMessageId` no se envíen al prompt si ya están cubiertos por el resumen.

- [x] **3.3 Generador Asíncrono de Resumen (`ConversationSummarizer`):**
  - Diseñar un prompt de síntesis compacto optimizado para modelos ligeros (Qwen 0.5B / 1.5B / Llama 1B):
    `"Resume los puntos clave, acuerdos y datos del siguiente diálogo en español de forma concisa:"`
  - Implementar la lógica de disparo cuando los mensajes descartados (`droppedHistoryMessages > 0` o conteo de tokens > 75% del presupuesto).
  - Ejecutar la síntesis de forma no bloqueante sin interrumpir la interacción del usuario.

- [x] **3.4 Pruebas y Validación:**
  - Tests unitarios en JVM (`ConversationSummarizerTest`, `ContextBuilderSummaryTest`).
  - Prueba de estrés con 100+ mensajes simulados verificando que el prompt total se mantenga dentro del presupuesto.

---

### 📌 Fase 4: Aislamiento Estricto de Memoria en Room (*Scope GLOBAL vs CHAT*)
> **Objetivo:** Unificar la memoria de largo plazo en Room SQLite y evitar que recuerdos personales de un chat aparezcan en chats diferentes.

- [ ] **4.1 Migración de `SqlMemoryStore` a Room:**
  - Crear `MemoryEntity` dentro de `PrismDatabase.kt` con soporte para:
    `id`, `sourceChatId`, `scope` (`GLOBAL` | `CHAT`), `fact`, `confidence`, `accessCount`, `createdAt`, `updatedAt`.
  - Crear `MemoryDao.kt` con consultas reactivas (`Flow<List<MemoryEntity>>`).
  - Migrador automático de datos desde la antigua `prism_memory.db` hacia `PrismDatabase`.

- [ ] **4.2 Filtro Estricto Anti-Fuga de Privacidad:**
  - Modificar la recuperación de memorias para que un chat solo consulte:
    `WHERE scope = 'GLOBAL' OR (scope = 'CHAT' AND sourceChatId = :currentChatId)`.
  - Normalización en español (ignorar tildes, mayúsculas y signos).

- [ ] **4.3 Extracción Asistida y Confirmable:**
  - Evitar alucinaciones de modelos pequeños implementando un detector de hechos clave posterior a la respuesta.
  - Presentar la memoria detectada en la UI como una sugerencia interactiva tipo chip (*"¿Recordar: 'Prefieres respuestas en Python'? [Guardar] [Descartar]"*).

- [ ] **4.4 Pruebas y Validación:**
  - Test unitario: Crear hechos en Chat A y verificar que Chat B obtenga únicamente hechos globales y jamás los privados de Chat A.
  - Validación de migración SQLite a Room sin duplicaciones.

---

### 📌 Fase 5: RAG Documental Integrado al Flujo Normal de Chat
> **Objetivo:** Conectar la búsqueda semántica local directamente a las respuestas cotidianas del chat, con atribución de fuentes y citas.

- [ ] **5.1 Esquema de Documentos y Colecciones en Room:**
  - Entidades en Room:
    - `DocumentEntity`: ID, título, hash SHA-256, URI original, total páginas, tamaño en bytes.
    - `ChunkEntity`: ID, documentId, número de fragmento, página/sección, texto, embedding BLOB.
    - `ChatCollectionCrossRef`: Tabla de unión para asociar qué colecciones documentales consulta cada chat.

- [ ] **5.2 Conexión en `GenerationOrchestrator`:**
  - Al recibir una pregunta del usuario, verificar si el chat tiene colecciones asignadas.
  - Ejecutar búsqueda semántica top-k (similitud coseno) sobre los fragmentos de la colección asociada.
  - Formatear los fragmentos recuperados e inyectarlos directamente en el argumento `ragContext` de `ContextBuilder.prepare(...)`.

- [ ] **5.3 Modo Estricto y Citas Documentales:**
  - Si el umbral de similitud es inferior a 0.65 o no hay fragmentos relevantes, instruir al modelo para responder que la documentación no contiene la respuesta (evitar alucinaciones).
  - Incluir en el prompt el formato de cita requerido: `[Doc: <Título>, Pág: <N>]`.

- [ ] **5.4 Interfaz de Selección en Compose:**
  - Menú desplegable en la cabecera del chat para vincular/desvincular colecciones o documentos activos.
  - Indicador visual cuando una respuesta proviene de fragmentos RAG recuperados.

---

### 📌 Fase 6: Ingesta Real de PDFs y Documentos Estructurados
> **Objetivo:** Procesar libros, manuales y notas técnicas en formato PDF directamente en el Honor 200 sin colapsar la memoria RAM.

- [ ] **6.1 Extractor de Texto PDF por Páginas:**
  - Reemplazar el extractor dummy de `AttachmentTextExtractor.kt` por un motor de extracción página a página (vía `PdfRenderer` o librería nativa ligera).
  - Consumo de memoria acotado: procesar una página a la vez liberando buffers para evitar exceder 150 MB de memoria heap.

- [ ] **6.2 Chunking Inteligente con Metadatos:**
  - Segmentar el texto por párrafos lógicos respetando límites de 250 a 500 tokens.
  - Cada fragmento conserva: número de página, encabezado más cercano y hash del documento.

- [ ] **6.3 Ingesta en Background con Barra de Progreso:**
  - Servicio de indexación en segundo plano que genere embeddings en lotes (*batch size* adaptativo según la temperatura del SoC).
  - Notificación de progreso y botón para pausar/cancelar en cualquier momento.

- [ ] **6.4 Pruebas y Validación:**
  - Prueba de ingesta de un PDF real de 50 páginas en el dispositivo.
  - Verificación de que el consumo de RAM no presente picos abruptos durante la generación de embeddings.

---

### 📌 Fase 7: Gobernador Global de Memoria y Telemetría Completa
> **Objetivo:** Garantizar estabilidad absoluta durante horas de uso y registrar métricas reales de rendimiento del hardware.

- [ ] **7.1 Validador de Presupuesto Global de Memoria:**
  - Fórmula preventiva antes de cargar un modelo o expandir la ventana:
    $$\text{RAM Total Requerida} = \text{Pesos GGUF} + \text{KV Cache}(n\_ctx, n\_embd, n\_layers) + \text{Embeddings Buffer} + 1.2\text{ GB (Android SO + UI)}$$
  - Rechazo controlado o degradación automática a un contexto menor si la memoria estimada supera 8.5 GB (dejando 3.5 GB libres de los 12 GB).

- [ ] **7.2 Telemetría Completa en Room (`telemetry_runs`):**
  - Guardar por cada inferencia:
    - Identificador de modelo y cuantización.
    - Tamaño del prompt y tokens generados.
    - Tiempo hasta el primer token (TTFT en ms).
    - Velocidad de evaluación de prompt (t/s).
    - Velocidad de generación (t/s).
    - Nivel de batería antes/después y temperatura pico del SoC.

- [ ] **7.3 Panel de Métricas en UI:**
  - Pantalla de estadísticas con gráficas históricas de velocidad, consumo de energía y estabilidad térmica.

---

## 4. Próxima Acción Inmediata

La siguiente tarea de la secuencia es la **Fase 4: Aislamiento Estricto de Memoria en Room**, iniciando con:
1. Crear `MemoryEntity` y `MemoryDao` en `PrismDatabase`.
2. Migrar idempotentemente `prism_memory.db` al almacén Room principal.
3. Aplicar el filtro obligatorio `GLOBAL OR (CHAT AND sourceChatId = currentChatId)` y probar que no existan fugas entre chats.
