# LLM Local para Honor 200 (Qualcomm Snapdragon 7 Gen 3)

Ejecución local, privada y offline de Modelos de Lenguaje Grandes (LLMs) en formato GGUF directamente en el smartphone **Honor 200** (Snapdragon 7 Gen 3, 12 GB RAM, Adreno 720, Android 14/15/16).

Basado en la infraestructura de [PrismLocal](https://github.com/gthgomez/PrismLocal) y potenciado por el motor nativo [llama.cpp](https://github.com/ggml-org/llama.cpp) en C++20 con aceleración ARM NEON / KleidiAI.

---

## 📱 Hardware Objetivo

| Componente | Especificación (Honor 200) |
|---|---|
| **SoC** | Qualcomm Snapdragon 7 Gen 3 (4 nm TSMC) |
| **CPU** | 1× Cortex-A715 @ 2.63 GHz + 3× Cortex-A715 @ 2.40 GHz + 4× Cortex-A510 @ 1.80 GHz |
| **GPU** | Adreno 720 |
| **Memoria** | 12 GB LPDDR |
| **Almacenamiento** | 256 GB / 512 GB UFS |
| **Sistema Operativo** | Android 14/15/16 (MagicOS) |

---

## 🚀 Optimizaciones Específicas para Snapdragon 7 Gen 3

1. **Compilación Nativa `-O3` Global (`LLMHOST_OPTIMIZE_NATIVE`):**
   - Resuelve el cuello de botella donde Android Studio compila el código nativo a `-O0` en modo Debug, garantizando máximo rendimiento de inferencia en cualquier tipo de compilación.
2. **Inferencia Estable en CPU (ARM NEON + KleidiAI):**
   - Vulkan desactivado por defecto (`LLMHOST_ENABLE_VULKAN = false`) para evitar los fallos de `vkCmdBindPipeline` reportados en los drivers stock de la GPU Adreno 720. La CPU multinúcleo con 4 núcleos Cortex-A715 ofrece latencia predecible, cero caídas y bajo consumo térmico.
3. **Correcciones de Ventana de Contexto (`Engine.cpp`):**
   - Corrección de condiciones de borde (`<= limit`) y erradicación del doble descuento de headroom, evitando errores 426 en chats con historiales extensos.
   - Limpieza segura del ring buffer al cancelar generaciones.
4. **Almacenamiento Privado Seguro:**
   - Modelos GGUF alojados en almacenamiento interno privado (`context.filesDir/models`), evitando permisos volátiles y el escaneo innecesario de Android MediaStore.
5. **Interfaz de Usuario AMOLED True Black:**
   - Tema optimizado en negro absoluto (`#000000`) para paneles OLED con soporte de teclado `adjustResize`, menú lateral flotante y tipografía Inter Variable.

---

## 🧠 Modelos Recomendados y Rendimiento Estimado

| Modelo | Cuantización | Tamaño en Disco | RAM Requerida | Velocidad Estimada | Caso de Uso Recomendado |
|---|---|---|---|---|---|
| **Llama 3.2 1B Instruct** | `Q4_K_M` | ~800 MB | ~1.5 GB | **35 – 45 t/s** | Respuestas ultrarrápidas, resúmenes breves |
| **Qwen 2.5 1.5B Instruct** | `Q4_K_M` | ~1.1 GB | ~2.0 GB | **25 – 35 t/s** | Excelente razonamiento en español y código |
| **Llama 3.2 3B Instruct** | `Q4_K_M` | ~2.1 GB | ~3.5 GB | **12 – 18 t/s** | Máxima calidad y redacción avanzada |

> ⚠️ **Nota de memoria:** El catálogo contiene modelos experimentales mayores (como 27B Q1); se desaconseja su uso diario en el dispositivo para evitar el cierre por LMK (*Low Memory Killer*).

---

## 🛠️ Cómo Compilar y Ejecutar

### Requisitos Previos
- **JDK:** OpenJDK 17 LTS o superior.
- **Android SDK & NDK:** API 34+ / NDK 26+.
- **Android Studio:** Ladybug / Koala o superior.

### Compilación desde Terminal

```bash
# Clonar el repositorio
git clone https://github.com/dppablito4-oss/Llm_local_for_honor_200.git
cd Llm_local_for_honor_200/PrismLocal

# Ejecutar las pruebas unitarias
./gradlew testDevDebugUnitTest

# Compilar e instalar en tu Honor 200 conectado por ADB
./gradlew installDevDebug
```

---

## 🧪 Pruebas y Calidad

- **Tests Unitarios:** 100% aprobados con Gradle 9.4.1.
- **Connected Android Tests:** 29/29 tests activos pasados en dispositivo real Honor 200 (cobertura en `RealInferenceSmokeTest`, `ContextReplayTest`, `ServiceSwitchModelTest`, `EngineStressTest`, `StreamingTextStateTest`).

---

## 📄 Licencia y Atribución

Este proyecto incorpora código de [PrismLocal](https://github.com/gthgomez/PrismLocal) bajo licencia **Apache 2.0** y el motor de inferencia nativo [llama.cpp](https://github.com/ggml-org/llama.cpp) bajo licencia **MIT**. Consulta los archivos `LICENSE` y `NOTICE` dentro de `PrismLocal/` para más detalles.
