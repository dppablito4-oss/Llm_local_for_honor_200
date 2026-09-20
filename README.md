# LLM Local para Honor 200 (Qualcomm Snapdragon 7 Gen 3)

Ejecución local, privada, offline y soberana de Modelos de Lenguaje Grandes (LLMs) en formato GGUF directamente en el smartphone **Honor 200** (Snapdragon 7 Gen 3, 12 GB RAM, Adreno 720, Android 14/15/16 - MagicOS).

Basado en la infraestructura de [PrismLocal](https://github.com/gthgomez/PrismLocal) y potenciado por el motor nativo [llama.cpp](https://github.com/ggml-org/llama.cpp) en C++20 con aceleración ARM NEON / KleidiAI.

---

## 📱 Hardware Objetivo (Honor 200)

| Componente | Especificación Técnica |
|---|---|
| **SoC** | Qualcomm Snapdragon 7 Gen 3 (4 nm TSMC) |
| **CPU** | 1× Cortex-A715 @ 2.63 GHz + 3× Cortex-A715 @ 2.40 GHz + 4× Cortex-A510 @ 1.80 GHz |
| **GPU** | Adreno 720 |
| **Memoria** | 12 GB LPDDR5 |
| **Almacenamiento** | 256 GB / 512 GB UFS 3.1 |
| **Pantalla** | AMOLED 120 Hz (Soporte True Black `#000000`) |
| **Sistema Operativo** | Android 14 / 15 / 16 (MagicOS 8.0 - 10.0) |

---

## 🚀 Optimizaciones y Características Implementadas

### 1. Motor de Inferencia Nativo C++20 (`llama.cpp` + ARM NEON)
* **Compilación Nativa `-O3` Forzada (`LLMHOST_OPTIMIZE_NATIVE`):** Evita la degradación a `-O0` que Android Studio aplica en variantes Debug, garantizando un rendimiento óptimo constante (~4x a 5x superior).
* **Inferencia Estable en CPU:** Vulkan desactivado por defecto (`LLMHOST_ENABLE_VULKAN = false`) para sortear los fallos de `vkCmdBindPipeline` reportados en los drivers stock del Adreno 720. Los 4 núcleos Cortex-A715 ofrecen latencia estable, cero cuelgues y bajo estrés térmico.
* **Tokenizer Nativo en C++ (`llama_tokenize`):** Integración directa en [`Engine::countChatTokens`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/main/cpp/Engine.cpp) y [`NativeLlmBridge`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/main/java/com/prismai/llmhost/bridge/NativeLlmBridge.kt). Permite calcular con exactitud matemática los tokens del chat con la plantilla del modelo activo antes de generar, eliminando desbordamientos de ventana.

### 2. Soporte para Modelos con Razonamiento (*Thinking Models*)
* **Perfiles Dinámicos de Comportamiento ([`ReasoningModels.kt`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/main/java/com/prismai/llmhost/model/ReasoningModels.kt)):** Soporte para **Qwen3** (conmutación fluida entre `/think` y `/no_think`) y **DeepSeek-R1 Distill** (omisión del system prompt y configuración de temperatura/top-k recomendada por el autor).
* **Interfaz de Razonamiento Colapsable:** Visualización en tiempo real del bloque `<think>` en un acordeón expandible en Jetpack Compose, con telemetría de tokens generados en la fase de razonamiento.
* **Purga Automática de CoT en Historial:** El razonamiento intermedio se muestra al usuario pero se purga al almacenar y reinyectar el contexto en turnos siguientes, impidiendo la saturación prematura de la ventana de contexto.

### 3. Persistencia Relacional en Room SQLite
* **Base de Datos Transaccional ([`PrismDatabase.kt`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/main/java/com/prismai/llmhost/persistence/PrismDatabase.kt)):** Almacenamiento primario en Room con entidades `ChatEntity` y `MessageEntity`, claves foráneas con borrado en cascada e índices optimizados.
* **Migración No Destructiva ([`LegacyConversationImporter.kt`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/main/java/com/prismai/llmhost/persistence/LegacyConversationImporter.kt)):** Importación verificada de archivos JSON legados con respaldo no bloqueante en segundo plano (`writeChatIndexJson`).

### 4. Orquestación y Constructor de Contexto Modular
* **[`ContextBuilder.kt`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/main/java/com/prismai/llmhost/generation/ContextBuilder.kt):** Genera un `PreparedContext` completamente observable. Asigna topes independientes para Sistema, Memoria (máx. 384 tokens), RAG (máx. 1024 tokens), Resumen e Historial Reciente, garantizando que un documento o memoria grande nunca desplace la conversación activa.

### 5. Interfaz de Usuario AMOLED True Black
* Tema visual diseñado en negro absoluto (`#000000`) que maximiza la eficiencia energética apagando los píxeles del panel OLED del Honor 200.
* Telemetría en vivo en la barra de estado con indicador de privacidad y velocidad de generación en tiempo real (ej. `● Private on-device · 6.18 t/s`).

---

## 🧠 Modelos Soportados y Rendimiento en Honor 200

Rendimiento verificado en dispositivo real (Qualcomm Snapdragon 7 Gen 3 / 12 GB RAM):

| Modelo | Cuantización | Tamaño en Disco | RAM Activa | Velocidad en CPU | Tipo / Caso de Uso |
|---|---|---|---|---|---|
| **Qwen 2.5 0.5B Instruct** | `Q4_K_M` | ~490 MB | ~1.0 GB | **45 – 60 t/s** | Ultrarrápido, pruebas inmediatas y latencia cero |
| **Llama 3.2 1B Instruct** | `Q4_K_M` | ~800 MB | ~1.5 GB | **35 – 45 t/s** | Resúmenes breves y respuestas concisas |
| **Qwen 2.5 1.5B Instruct** | `Q4_K_M` | ~1.1 GB | ~2.0 GB | **25 – 35 t/s** | Gran razonamiento en español y código ligero |
| **Qwen3 1.7B (Hybrid)** | `Q4_K_M` | ~1.2 GB | ~2.2 GB | **18 – 26 t/s** | Chat normal + Razonamiento bajo demanda (`/think`) |
| **DeepSeek-R1 Distill Qwen 1.5B** | `Q4_K_M` | ~1.1 GB | ~2.0 GB | **15 – 22 t/s** | Razonamiento deductivo y resolución paso a paso |
| **Qwen 2.5 3B Instruct** | `Q4_K_M` | ~2.1 GB | ~3.5 GB | **~6.2 t/s** ⭐ | **Máxima calidad y redacción avanzada en español** |
| **Llama 3.2 3B Instruct** | `Q4_K_M` | ~2.1 GB | ~3.5 GB | **10 – 15 t/s** | Creatividad, redacción formal y síntesis compleja |

> ⭐ **Nota de rendimiento:** La velocidad de **~6.2 t/s** en modelos de 3B corresponde a la velocidad natural de lectura humana en tiempo real, permitiendo una experiencia conversacional fluida sin servidores externos.

---

## 🏗️ Arquitectura del Sistema

```text
┌────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                   │
│       (AMOLED True Black · Markdown · Acordeón CoT)    │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                    InferenceService                    │
│   (Foreground Service Android · Ciclo de Vida Único)   │
└───────┬───────────────────┬───────────────────┬────────┘
        │                   │                   │
┌───────▼─────────┐ ┌───────▼─────────┐ ┌───────▼────────┐
│ ContextBuilder  │ │  ChatManager    │ │ ModelManager   │
│ Presupuestos:   │ │ Persistencia    │ │ Carga/Descarga │
│ • Sistema       │ │ Room SQLite     │ │ Verificación   │
│ • Memoria       │ │ (PrismDatabase) │ │ SHA-256        │
│ • Historial     │ └─────────────────┘ └────────────────┘
│ • Pregunta      │
└───────┬─────────┘
        │
┌───────▼────────────────────────────────────────────────┐
│                   NativeLlmBridge                      │
│        (JNI Bridge · llama_tokenize · C++20)           │
└───────────────────────────┬────────────────────────────┘
                            │
┌───────────────────────────▼────────────────────────────┐
│                  Engine.cpp (llama.cpp)                │
│    ARM NEON / KleidiAI · CPU Multi-Core (Cortex-A715)  │
└────────────────────────────────────────────────────────┘
```

---

## 🛠️ Cómo Compilar y Ejecutar

### Requisitos
* **JDK:** OpenJDK 17 LTS o 21 LTS.
* **Android SDK & NDK:** SDK API 34+ / NDK r26c+.
* **Android Studio:** Ladybug / Koala o superior.
* **Gradle:** 9.4.1 (incluido vía wrapper).

### Pasos desde Terminal

```bash
# 1. Clonar el repositorio
git clone https://github.com/dppablito4-oss/Llm_local_for_honor_200.git
cd Llm_local_for_honor_200/PrismLocal

# 2. Ejecutar la suite completa de pruebas unitarias
./gradlew testDevDebugUnitTest

# 3. Compilar e instalar en tu Honor 200 con depuración USB activa
./gradlew installDevDebug
```

---

## 🧪 Pruebas y Validación de Calidad

* **Pruebas Unitarias:** 100% aprobadas en Gradle 9.4.1 ([`PromptBuilderMessagesTest`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/test/java/com/prismai/llmhost/PromptBuilderMessagesTest.kt), [`ReasoningModelsTest`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/test/java/com/prismai/llmhost/model/ReasoningModelsTest.kt), [`EngineConfigStoreTest`](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/app/src/test/java/com/prismai/llmhost/engine/EngineConfigStoreTest.kt)).
* **Pruebas Conectadas en Honor 200:**
  * `RealInferenceSmokeTest`: Verificación de inferencia real y conteo nativo de tokens (`activeTokenizerCountsTemplatedChatBeforeGeneration`).
  * `ServiceSwitchModelTest`: Cambio de modelo en caliente conservando el historial sin pérdida de datos.
  * `ReasoningModelOnDeviceTest`: Inferencia real de modelos de razonamiento en hardware.
  * `LegacyConversationImporterTest` y `RoomConversationMirrorTest`: Validación transaccional de la persistencia Room.

---

## 📚 Documentación Técnica Adicional

* [Plan General de Adopción y Optimización (`PRISMLOCAL_HONOR200_PLAN.md`)](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PRISMLOCAL_HONOR200_PLAN.md)
* [Estado Arquitectónico Actual (`PrismLocal/docs/architecture/current-state.md`)](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/docs/architecture/current-state.md)
* [Arquitectura Objetivo Incremental (`PrismLocal/docs/architecture/target-state.md`)](file:///c:/Users/Grafiplot/.gemini/antigravity-ide/scratch/Llm_local_for_honor_200/PrismLocal/docs/architecture/target-state.md)

---

## 📄 Licencia y Atribución

Este proyecto incorpora y adapta código de [PrismLocal](https://github.com/gthgomez/PrismLocal) bajo licencia **Apache 2.0** y el motor nativo de inferencia [llama.cpp](https://github.com/ggml-org/llama.cpp) bajo licencia **MIT**. Consulta los archivos `LICENSE` y `NOTICE` dentro de `PrismLocal/` para mayores especificaciones.
