# PrismLocal en Honor 200 --- Plan de adopción, pruebas y optimización

**Estado:** Actualizado después de auditoría del código real\
**Versión:** 0.3\
**Dispositivo objetivo inicial:** Honor 200 (12 GB RAM / 256 GB
almacenamiento / Snapdragon 7 Gen 3 / Android 16 / MagicOS 10)\
**Repositorio base:** https://github.com/gthgomez/PrismLocal\
**Motor de inferencia:** `llama.cpp` integrado por PrismLocal\
**Objetivo de esta etapa:** evolucionar el proyecto ya compilado y probado
en el Honor 200 hacia chat persistente, contexto largo controlado, memoria
aislada y RAG documental, conservando las optimizaciones de inferencia que
ya funcionan.

> **Corrección de auditoría (19 de septiembre de 2026):** el proyecto ya fue
> clonado, compilado, instalado y probado con modelos reales. Room es ahora la
> fuente de verdad de chats y mensajes; JSON se regenera solo como respaldo.
> También se completó `ContextBuilder`, incluido el conteo con el tokenizer del
> GGUF activo. Memoria de largo plazo y vectores siguen en bases SQLite separadas.
> RAG puede almacenar y consultar fragmentos, pero aún no se inyecta en el chat
> normal. El estado y destino detallados están en
> `PrismLocal/docs/architecture/current-state.md` y
> `PrismLocal/docs/architecture/target-state.md`.

------------------------------------------------------------------------

## 1. Idea principal

El proyecto no comenzará creando un runtime LLM para Android desde cero.

La estrategia será utilizar **PrismLocal** como base, porque ya resuelve
una parte considerable de la infraestructura necesaria para ejecutar
modelos GGUF directamente en Android.

El orden de trabajo será estricto:

``` text
CLONAR
   ↓
COMPILAR SIN MODIFICAR
   ↓
EJECUTAR EN EL HONOR 200
   ↓
VERIFICAR FUNCIONES EXISTENTES
   ↓
ESTABLECER UNA LÍNEA BASE
   ↓
BENCHMARK DE MODELOS
   ↓
IDENTIFICAR CUELLOS DE BOTELLA
   ↓
OPTIMIZAR PARA EL HARDWARE
   ↓
RECIÉN DESPUÉS PERSONALIZAR
```

La primera versión propia no intentará agregar funciones nuevas. Primero
debemos saber qué tan bien funciona PrismLocal tal como fue diseñado.

------------------------------------------------------------------------

# 2. Repositorio base

## PrismLocal

Repositorio:

https://github.com/gthgomez/PrismLocal

PrismLocal se presenta como una aplicación Android open source para
ejecutar **LLM GGUF directamente en el dispositivo**.

Su arquitectura resulta apropiada para este proyecto porque no es
simplemente una interfaz de chat. Ya contiene infraestructura para:

-   inferencia local;
-   `llama.cpp`;
-   JNI;
-   gestión del ciclo de vida del modelo;
-   streaming de tokens;
-   almacenamiento de modelos;
-   conversaciones;
-   RAG local;
-   almacenamiento vectorial;
-   control térmico;
-   control de memoria;
-   batería;
-   descargas de modelos;
-   herramientas;
-   funcionamiento local-first.

La licencia declarada del proyecto es **Apache License 2.0**.
`llama.cpp`, utilizado internamente, mantiene su licencia
correspondiente.

Antes de distribuir una versión derivada se deberán conservar los
avisos, atribuciones y condiciones de licencia correspondientes.

------------------------------------------------------------------------

# 3. Qué hace actualmente PrismLocal

Según la documentación actual del repositorio, PrismLocal integra un
runtime `llama.cpp` en una aplicación Android mediante una capa JNI.

Su arquitectura general puede representarse así:

``` text
┌──────────────────────────────────┐
│           ANDROID UI             │
│       Jetpack Compose            │
│                                  │
│ Chat / controles / configuración │
└────────────────┬─────────────────┘
                 │
                 ▼
┌──────────────────────────────────┐
│        ANDROID PLATFORM          │
│                                  │
│ InferenceService                 │
│ ThermalBatteryGovernor           │
│ MemoryGovernor                   │
│ ModelStorageManager              │
│ VectorStore                      │
└────────────────┬─────────────────┘
                 │
                 ▼
┌──────────────────────────────────┐
│              JNI                 │
│       NativeLlmBridge            │
└────────────────┬─────────────────┘
                 │
                 ▼
┌──────────────────────────────────┐
│       MOTOR NATIVO C++20         │
│                                  │
│ Engine.cpp                       │
│ llama.cpp                        │
│ ggml                             │
└────────────────┬─────────────────┘
                 │
                 ▼
             MODELO GGUF
```

Esto es importante: **no necesitamos construir inicialmente el puente
Kotlin ↔ C++ ↔ llama.cpp**.

------------------------------------------------------------------------

# 4. Componentes que ya contiene la base

## 4.1 Interfaz Android

PrismLocal utiliza **Jetpack Compose** para su interfaz.

La UI se comunica con el servicio de inferencia mediante estados/eventos
en lugar de ejecutar directamente el modelo desde la pantalla.

Esto nos interesa porque mantiene separadas:

``` text
UI
│
├── presentación
├── entrada del usuario
└── controles

       ≠

motor LLM
```

La interfaz podrá cambiarse mucho más adelante sin tener que reescribir
necesariamente el motor.

------------------------------------------------------------------------

## 4.2 Servicio de inferencia

El proyecto utiliza un **Foreground InferenceService**.

Su responsabilidad es mantener y coordinar la inferencia
independientemente de la pantalla de Compose.

Conceptualmente:

``` text
ChatScreen
     │
     ▼
InferenceService
     │
     ▼
NativeLlmBridge
     │
     ▼
llama.cpp
```

Esta separación será conservada durante las primeras etapas.

------------------------------------------------------------------------

## 4.3 llama.cpp

PrismLocal integra una versión de `llama.cpp`.

`llama.cpp` proporciona la parte pesada de la inferencia:

-   carga de modelos GGUF;
-   creación del contexto;
-   tokenización;
-   procesamiento del prompt;
-   generación;
-   sampling;
-   administración del KV cache;
-   operaciones tensoriales;
-   ejecución sobre los backends disponibles.

Por tanto, nuestro proyecto inicialmente **no será un motor LLM nuevo**.

Será una aplicación Android especializada construida encima de un
runtime existente.

------------------------------------------------------------------------

# 5. JNI

Android/Kotlin no llama directamente a todas las funciones internas de
`llama.cpp`.

PrismLocal dispone de un puente JNI.

``` text
Kotlin
  │
  ▼
JNI
  │
  ▼
C++
  │
  ▼
llama.cpp
```

La documentación del proyecto describe un puente JNI protegido para
acceso thread-safe.

También utiliza un buffer previamente asignado para transportar
fragmentos de generación y reducir asignaciones innecesarias durante el
streaming.

En la primera etapa:

> **NO modificar JNI.**

Primero debemos demostrar que el runtime existente funciona
correctamente en el Honor 200.

------------------------------------------------------------------------

# 6. Streaming

PrismLocal puede entregar la respuesta progresivamente.

En lugar de:

``` text
esperar 20 segundos
↓
mostrar respuesta completa
```

la arquitectura permite:

``` text
token
↓
token
↓
token
↓
token
↓
...
```

Esto será importante para medir dos métricas distintas:

-   **TTFT --- Time To First Token**
-   **Generation speed --- tokens/s**

No deben confundirse.

Un modelo puede empezar rápido pero generar lentamente, o tardar más en
comenzar y posteriormente producir tokens con mayor velocidad.

------------------------------------------------------------------------

# 7. Cancelación de generación

La implementación nativa contempla comprobaciones de cancelación durante
la inferencia.

Esto permite que el usuario pueda detener una generación sin esperar
necesariamente a que termine toda la respuesta.

Debe probarse específicamente durante la validación inicial.

------------------------------------------------------------------------

# 8. Control térmico

PrismLocal incorpora un componente denominado:

`ThermalBatteryGovernor`

Su función incluye observar el estado térmico reportado por Android.

La documentación actual indica que puede pausar la inferencia cuando el
dispositivo alcanza estados térmicos severos.

Esto es especialmente importante para nuestro proyecto.

Un teléfono puede obtener:

``` text
20 tok/s
```

durante una prueba de 20 segundos y posteriormente caer
significativamente después de varios minutos por temperatura.

Por eso nuestro benchmark no medirá únicamente rendimiento máximo.

Medirá también:

``` text
rendimiento inicial
rendimiento sostenido
temperatura
throttling
```

------------------------------------------------------------------------

# 9. Control de batería

El mismo sistema contempla el nivel de batería.

La documentación actual del repositorio señala un umbral de protección
cuando la batería cae por debajo del 15 %.

No cambiaremos inicialmente este comportamiento.

Durante los tests registraremos cuánto consume cada combinación de
modelo/configuración.

------------------------------------------------------------------------

# 10. Control de memoria

PrismLocal incorpora un:

`MemoryGovernor`

Su propósito es observar presión de memoria del sistema y reducir el
riesgo de errores por falta de RAM.

Para nuestro Honor 200 esta parte será crítica.

El dispositivo posee 12 GB de RAM física, pero:

``` text
RAM TOTAL
   ≠
RAM COMPLETAMENTE DISPONIBLE PARA EL LLM
```

Android, MagicOS, procesos del sistema, GPU, la propia aplicación,
contexto y buffers también consumen memoria.

Por ello no diseñaremos nuestras pruebas suponiendo que los 12 GB
completos están disponibles.

La llamada "Turbo RAM" tampoco será considerada equivalente a RAM física
para dimensionar el modelo.

------------------------------------------------------------------------

# 11. Gestión de modelos

PrismLocal incluye infraestructura para almacenar y descargar modelos
GGUF.

La documentación del proyecto describe:

-   descargas mediante WorkManager;
-   posibilidad de reanudar descargas;
-   verificación SHA-256;
-   almacenamiento local del modelo.

Esto nos evita construir inmediatamente nuestro propio downloader.

En la primera etapa se utilizará el sistema existente.

------------------------------------------------------------------------

# 12. GGUF

El formato principal será:

`GGUF`

Los modelos estarán cuantizados cuando sea conveniente.

Ejemplos de cuantizaciones que posteriormente podremos evaluar:

``` text
Q4
Q5
Q6
Q8
```

No asumiremos que una cuantización superior es automáticamente la mejor
para móvil.

Compararemos:

``` text
calidad
RAM
tamaño
TTFT
tok/s
temperatura
consumo
```

------------------------------------------------------------------------

# 13. Persistencia local

PrismLocal persiste chats y mensajes en Room mediante `prismlocal.db`.
`chat_index.json` y `chats/<chatId>.json` se regeneran de forma asíncrona como
respaldo de recuperación, pero ya no gobiernan el arranque ni las lecturas.

La memoria de largo plazo utiliza `prism_memory.db` y los fragmentos vectoriales
utilizan `prism_vector_store.db`, ambas mediante `SQLiteOpenHelper`. Su migración
y aislamiento se mantienen pendientes para no mezclar cambios de riesgo.

Esto resulta adecuado para nuestro objetivo de mantener los datos
principalmente dentro del dispositivo.

------------------------------------------------------------------------

# 14. RAG existente

Una de las principales razones para utilizar PrismLocal es que ya
contiene infraestructura de **Retrieval-Augmented Generation (RAG)**.

Conceptualmente:

``` text
DOCUMENTO
    │
    ▼
fragmentación
    │
    ▼
representación/búsqueda
    │
    ▼
VectorStore
    │
    ▼
fragmentos relevantes
    │
    ▼
contexto para el LLM
    │
    ▼
respuesta
```

La implementación actual contiene `RagManager`, fragmentación, embeddings,
almacenamiento SQLite y búsqueda por similitud coseno. Sin embargo,
`buildRagContext()` todavía no participa en la generación normal. Tampoco hay
documentos, colecciones, páginas, citas ni relación chat-documento.

No reemplazaremos el almacenamiento vectorial al principio. Primero añadiremos
metadatos, aislamiento y conexión con el constructor de contexto.

Primero comprobaremos:

1.  qué formatos maneja correctamente;
2.  cómo divide documentos;
3.  cuánto tarda en procesarlos;
4.  cuánta memoria utiliza;
5.  calidad de recuperación;
6.  comportamiento con documentos grandes;
7.  comportamiento en español;
8.  qué información conserva para las fuentes.

Solo después decidiremos qué mejorar.

------------------------------------------------------------------------

# 15. Privacidad

El diseño de PrismLocal es **local-first**.

La inferencia y almacenamiento de conversaciones pueden permanecer en el
dispositivo.

El repositorio declara que no incluye SDK de anuncios ni recopiladores
automáticos de telemetría.

Sin embargo, existen acciones que pueden requerir red, por ejemplo la
descarga de modelos y determinadas herramientas iniciadas por el
usuario.

Antes de convertir nuestro fork en una aplicación de uso diario
realizaremos nuestra propia auditoría del tráfico de red.

------------------------------------------------------------------------

# 16. Compatibilidad Android moderna

El repositorio actual declara:

``` text
Compile SDK: 36
Target SDK: 36
Min SDK: 26
JDK: 17
NDK: 28.2.13676358
CMake: 3.22.1
```

También incluye medidas relacionadas con páginas de memoria de 16 KB y
Android 15/16.

Esto es especialmente conveniente porque nuestro dispositivo objetivo
utiliza Android 16.

------------------------------------------------------------------------

# 17. Hardware objetivo inicial

Nuestro primer hardware conocido será:

``` text
DEVICE PROFILE

Device:
Honor 200

SoC:
Qualcomm Snapdragon 7 Gen 3

RAM:
12 GB física

Storage:
256 GB

OS:
Android 16

Skin:
MagicOS 10

Architecture:
ARM64
```

El objetivo NO será introducir optimizaciones específicas desde el
primer commit.

Primero estableceremos el rendimiento del proyecto original.

------------------------------------------------------------------------

# 18. Regla fundamental del desarrollo

## Primero medir. Después modificar.

No haremos esto:

``` text
clonar
↓
cambiar JNI
↓
cambiar llama.cpp
↓
activar optimizaciones
↓
cambiar RAG
↓
probar
```

Porque si algo mejora o empeora no sabremos cuál modificación fue
responsable.

Haremos:

``` text
UPSTREAM LIMPIO
       ↓
    TEST A
       ↓
CAMBIO PEQUEÑO
       ↓
    TEST B
       ↓
comparar A vs B
```

Cada optimización deberá tener evidencia.

------------------------------------------------------------------------

# 19. Fase 0 --- Preparar el entorno

Instalar:

-   Git;
-   JDK 17;
-   Android Studio;
-   Android SDK correspondiente;
-   Android NDK requerido por el proyecto;
-   CMake requerido;
-   ADB.

Verificar:

``` bash
java -version
adb version
```

En Android Studio deberán instalarse las versiones exactas que requiera
el repositorio.

No actualizar dependencias arbitrariamente antes de obtener el primer
build funcional.

------------------------------------------------------------------------

# 20. Fase 1 --- Clonar sin modificar

Comando base documentado por PrismLocal:

``` bash
git clone --recurse-submodules https://github.com/gthgomez/PrismLocal.git
cd PrismLocal
```

Verificar:

``` bash
git status
git submodule status
```

El árbol debe permanecer limpio.

``` bash
git status
```

debería indicar que no existen modificaciones propias.

------------------------------------------------------------------------

# 21. Registrar el upstream

Cuando creemos nuestro fork:

``` bash
git remote -v
```

Mantendremos conceptualmente:

``` text
origin
→ nuestro fork

upstream
→ PrismLocal original
```

Por ejemplo:

``` bash
git remote add upstream https://github.com/gthgomez/PrismLocal.git
```

Esto permitirá incorporar posteriormente mejoras del proyecto original
sin perder nuestras modificaciones.

------------------------------------------------------------------------

# 22. Fase 2 --- Compilar upstream puro

Antes de cambiar una línea:

### Tests

En Windows, el README actual proporciona:

``` powershell
.\gradlew.bat --no-daemon :app:testDevDebugUnitTest :app:testPlayDebugUnitTest
```

### APK debug

``` powershell
.\gradlew.bat --no-daemon :app:assembleDevDebug
```

En sistemas Unix-like se utilizará el wrapper correspondiente:

``` bash
./gradlew --no-daemon :app:assembleDevDebug
```

Nuestro primer objetivo técnico será simplemente:

``` text
BUILD SUCCESSFUL
```

No optimización.

No UI propia.

No modelos especiales.

No cambios de backend.

------------------------------------------------------------------------

# 23. Fase 3 --- Instalar upstream puro

Con el Honor conectado mediante ADB:

``` bash
adb devices
```

El teléfono debe aparecer autorizado.

Instalar el APK generado mediante Android Studio/Gradle o ADB.

Después comprobar:

``` text
abre
↓
no crash
↓
permite acceder a funciones básicas
↓
puede cargar modelo
↓
puede iniciar inferencia
```

------------------------------------------------------------------------

# 24. Fase 4 --- Smoke test

Antes del benchmark completo realizaremos una prueba básica.

Checklist:

``` text
[ ] aplicación abre
[ ] UI responde
[ ] descarga/importa GGUF
[ ] SHA-256 funciona
[ ] modelo carga
[ ] prompt se tokeniza
[ ] generación comienza
[ ] streaming funciona
[ ] botón cancelar funciona
[ ] conversación se conserva
[ ] descargar modelo de RAM funciona
[ ] aplicación sobrevive cambio de pantalla
[ ] no hay crash al bloquear/desbloquear
[ ] comportamiento correcto en segundo plano
[ ] gobernador térmico funciona
[ ] gobernador de memoria funciona
```

Si alguna prueba falla, todavía NO optimizamos rendimiento.

Primero corregimos compatibilidad.

------------------------------------------------------------------------

# 25. Fase 5 --- Baseline

Una vez PrismLocal funcione sin modificaciones crearemos nuestro
**baseline**.

Ejemplo:

``` text
BASELINE ID:
HONOR200-UPSTREAM-001

Commit PrismLocal:
xxxxxxxx

llama.cpp commit:
xxxxxxxx

Android:
16

MagicOS:
10

Battery:
100 %

Temperature:
reposo

Model:
modelo de referencia

Quantization:
Q4_K_M

Context:
4096

Threads:
default

GPU:
configuración upstream
```

Este baseline será nuestro punto cero.

------------------------------------------------------------------------

# 26. No empezar con veinte modelos

Primero utilizaremos un modelo pequeño y conocido únicamente para
validar el pipeline.

Después crearemos la batería de benchmarks.

Categorías previstas:

``` text
~0.5–0.8B
~1–2B
~3B
~4B
~7–8B
```

La lista concreta se fijará cuando comience el benchmark, porque modelos
y conversiones GGUF disponibles pueden cambiar.

------------------------------------------------------------------------

# 27. Fase 6 --- Benchmark de hardware

Cada modelo se probará bajo condiciones reproducibles.

Registrar:

``` text
MODEL
name
parameters
quantization
GGUF size

RUNTIME
context size
threads
batch
GPU configuration

LOAD
load time
RAM before
RAM after
peak RAM

INFERENCE
prompt tokens
prompt processing tok/s
TTFT
generation tok/s
generated tokens

THERMAL
initial
5 min
10 min
15 min
30 min

ENERGY
battery start
battery end
duration

STABILITY
crashes
OOM
thermal pause
unexpected stop
```

------------------------------------------------------------------------

# 28. Benchmark corto vs sostenido

Haremos dos pruebas.

## Burst

Duración corta.

Objetivo:

``` text
rendimiento máximo
```

## Sustained

Generación repetida durante varios minutos.

Objetivo:

``` text
rendimiento real sostenido
```

Esto permitirá detectar thermal throttling.

------------------------------------------------------------------------

# 29. Fase 7 --- Benchmark de calidad

La velocidad por sí sola no decide el modelo.

Crearemos un conjunto fijo de prompts en español.

Categorías:

``` text
conversación
redacción
resumen
seguimiento de instrucciones
extracción
RAG
razonamiento
matemática
código
resistencia a inventar información
```

Cada modelo recibe exactamente las mismas entradas.

------------------------------------------------------------------------

# 30. Matriz final de cada modelo

Ejemplo:

``` text
MODEL: XXXXX 3B Q4_K_M

Size            2.0 GB
Peak RAM        X.X GB
TTFT            X.X s
Generation      XX tok/s
15 min          XX tok/s
Temperature     XX °C
Battery         X % / 15 min

Spanish         X/10
Writing         X/10
Summary         X/10
RAG             X/10
Reasoning       X/10
Code            X/10
```

No utilizaremos un supuesto porcentaje universal de "inteligencia".

Compararemos cada modelo por tareas.

------------------------------------------------------------------------

# 31. Fase 8 --- Identificar el cuello de botella

Solo después de obtener datos preguntaremos:

``` text
¿CPU?
¿GPU?
¿RAM?
¿KV cache?
¿context size?
¿threads?
¿batch?
¿thermal throttling?
¿lectura de almacenamiento?
¿JNI?
¿UI?
```

No optimizaremos aquello que no sea un problema medido.

------------------------------------------------------------------------

# 32. Primera personalización: HardwareProfile

La primera función propia importante puede ser un perfil de hardware.

Conceptualmente:

``` text
HardwareProfile
├── device
├── SoC
├── physical RAM
├── Android version
├── thermal characteristics
├── recommended context
├── recommended threads
├── recommended model class
└── recommended backend settings
```

Primer perfil:

``` text
Honor 200
Snapdragon 7 Gen 3
12 GB
Android 16
```

Los valores recomendados se llenarán con los resultados reales, no con
estimaciones.

------------------------------------------------------------------------

# 33. Segunda personalización: Benchmark Mode

Después del baseline podremos añadir a la propia aplicación una
pantalla:

``` text
Benchmark
```

Con:

``` text
Model
Quant
Context
Threads
Backend
Prompt

[ RUN ]
```

Salida:

``` text
Load:        X.XX s
TTFT:        X.XX s
Prompt:      XX.X tok/s
Generation:  XX.X tok/s
RAM:         XXXX MB
Temperature: XX °C
Battery:     XX %
```

Esto convertiría el fork en una herramienta útil para optimizar otros
Android en el futuro.

------------------------------------------------------------------------

# 34. Optimización de CPU

Cuando tengamos baseline podremos experimentar con:

-   número de threads;
-   afinidad si resulta justificable;
-   batch;
-   context;
-   configuraciones de `llama.cpp`.

Cada prueba cambia **una variable principal**.

Ejemplo:

``` text
TEST 01
threads = 4

TEST 02
threads = 6

TEST 03
threads = 8
```

Manteniendo todo lo demás igual.

------------------------------------------------------------------------

# 35. Optimización de GPU

La GPU Adreno del Snapdragon será investigada después de tener un
baseline CPU/actual.

No activaremos configuraciones experimentales solamente porque "deberían
ser más rápidas".

Se comparará:

``` text
CPU
vs
GPU/offload disponible
```

Midiendo:

``` text
TTFT
tok/s
RAM
temperatura
energía
estabilidad
```

Si la GPU aumenta tokens/s pero provoca peor rendimiento sostenido por
temperatura, eso deberá quedar reflejado.

------------------------------------------------------------------------

# 36. NPU / Hexagon

Hexagon queda fuera de la primera etapa.

Ruta:

``` text
V0.x
llama.cpp estable

V1.x
CPU/GPU optimizado

FUTURO
evaluación Hexagon/NPU
```

No haremos depender el proyecto de una integración NPU experimental.

------------------------------------------------------------------------

# 37. Contexto

El contexto tiene impacto directo sobre memoria.

Comenzaremos conservadoramente.

Escalones de prueba:

``` text
2048
4096
8192
16384
```

No utilizaremos contextos gigantes simplemente porque un modelo los
soporte teóricamente.

Buscaremos el máximo **práctico** para el Honor.

------------------------------------------------------------------------

# 38. Cuantización

Cuando encontremos un modelo prometedor, compararemos distintas
cuantizaciones del mismo modelo.

Ejemplo conceptual:

``` text
MODEL X

Q4
↓
Q5
↓
Q6
↓
Q8
```

Objetivo:

``` text
¿cuánta calidad ganamos?
vs
¿cuánta RAM/latencia/energía cuesta?
```

El punto óptimo puede no ser la cuantización de mayor precisión.

------------------------------------------------------------------------

# 39. Fase RAG

Solo después de tener inferencia estable comenzaremos a evaluar y
mejorar el RAG existente.

La inferencia ya es estable en el Honor 200. Antes de medir calidad RAG hay que
conectar la recuperación al chat y evitar que los documentos de una colección
se filtren a otra.

Primero:

``` text
RAG ORIGINAL DE PRISMLOCAL
```

Mediremos:

-   importación;
-   chunking;
-   almacenamiento;
-   búsqueda;
-   relevancia;
-   latencia;
-   documentos en español;
-   documentos largos;
-   consumo de RAM.

Después decidiremos cambios.

------------------------------------------------------------------------

# 40. Objetivo futuro del RAG

Queremos llegar a:

``` text
Pregunta
   │
   ▼
Embedding
   │
   ▼
Local Search
   │
   ▼
Top-K chunks
   │
   ▼
LLM
   │
   ▼
Respuesta
   │
   ├── documento
   ├── página
   └── evidencia
```

El objetivo será que el usuario pueda verificar la respuesta.

------------------------------------------------------------------------

# 41. Modo estricto futuro

Posteriormente se añadirá un modo de documentos con instrucciones
similares a:

``` text
Utiliza únicamente el contexto recuperado.

Si el contexto no contiene evidencia suficiente,
indica que la información no se encuentra en los documentos.

No completes información ausente mediante suposiciones.

Indica las fuentes utilizadas cuando estén disponibles.
```

El prompt será una capa de protección, no una garantía absoluta.

La recuperación y las referencias serán igualmente importantes.

------------------------------------------------------------------------

# 42. Model Manager futuro

PrismLocal ya tiene gestión de modelos.

Nuestra evolución deberá permitir mantener múltiples GGUF almacenados y
cargar únicamente el necesario.

``` text
STORAGE

Model A
Model B
Model C
Model D

      ↓

RAM

solo modelo activo
```

Cuando el usuario cambie:

``` text
stop generation
↓
release context
↓
unload model A
↓
confirm memory release
↓
load model B
↓
create context
↓
ready
```

Esto será más adecuado para 12 GB que mantener varios LLM grandes
simultáneamente.

------------------------------------------------------------------------

# 43. Especialización futura

Después de los benchmarks podremos decidir si tiene sentido utilizar
perfiles como:

``` text
FAST
GENERAL
DOCUMENT
REASONING
CODE
```

Pero estos nombres no se asignarán por tamaño.

Se asignarán por **resultados reales**.

Un 3B podría superar a otro 4B en determinada tarea.

------------------------------------------------------------------------

# 44. Router futuro

Mucho después podremos implementar:

``` text
USER
 │
 ▼
TASK CLASSIFIER
 │
 ├── general
 ├── document
 ├── code
 └── reasoning
        │
        ▼
MODEL MANAGER
        │
        ▼
modelo adecuado
```

Primero la selección será manual.

La automatización llegará cuando sepamos qué modelos funcionan mejor en
cada categoría.

------------------------------------------------------------------------

# 45. Internet

No será prioridad durante la optimización inicial.

La filosofía final será:

``` text
LOCAL FIRST
```

Internet se utilizará únicamente para funciones que realmente lo
necesiten:

-   descarga de modelos;
-   búsquedas actuales;
-   herramientas explícitamente activadas.

Los documentos privados no necesitarán abandonar el teléfono para el
flujo local.

------------------------------------------------------------------------

# 46. Qué NO modificar al principio

Durante la etapa baseline:

``` text
NO cambiar llama.cpp
NO reescribir JNI
NO cambiar arquitectura
NO hacer una migración masiva de todas las bases en un solo cambio
NO eliminar VectorStore antes de medir su límite real
NO cambiar governor térmico
NO activar NPU experimental
NO hacer router multimodelo
NO rediseñar UI
NO actualizar todas las dependencias
NO añadir funciones porque sí
```

Primero queremos saber:

> **¿Qué puede hacer PrismLocal original en este Honor 200?**

------------------------------------------------------------------------

# 47. Orden de modificaciones

Después del baseline:

``` text
1. Documentar estado real y arquitectura objetivo — COMPLETADO
2. Pruebas de persistencia y cambio de modelo — COMPLETADO
3. Room para chats y mensajes con importación de JSON — COMPLETADO
4. ContextBuilder y presupuesto por secciones — COMPLETADO
5. Resumen acumulativo de conversaciones largas
6. Aislamiento de memoria global/por chat
7. RAG conectado al chat y filtrado por colecciones
8. PDF textual por páginas
9. OCR opcional y citas
10. Métricas y presupuesto integral de RAM
11. Benchmark sostenido de la arquitectura completa
12. GPU/Vulkan experimental
13. Visión/multimodal
14. NPU/Hexagon experimental
```

------------------------------------------------------------------------

# 48. Estrategia Git

Nunca trabajar directamente sobre una única rama desordenada.

Propuesta:

``` text
main
│
├── upstream-sync
│
├── benchmark
│
├── honor200-profile
│
├── inference-tuning
│
├── rag-improvements
│
└── ui
```

Cada cambio importante deberá poder compararse contra baseline.

------------------------------------------------------------------------

# 49. Registro de experimentos

Crear:

``` text
/docs/benchmarks/
```

Ejemplo:

``` text
docs/
└── benchmarks/
    ├── honor200/
    │   ├── baseline.md
    │   ├── model-01.md
    │   ├── model-02.md
    │   ├── cpu-tests.md
    │   ├── gpu-tests.md
    │   └── thermal-tests.md
```

Los resultados importantes no deben existir únicamente en capturas o
mensajes.

------------------------------------------------------------------------

# 50. Plantilla de experimento

``` text
TEST ID:

DATE:

DEVICE:
Honor 200

SOC:
Snapdragon 7 Gen 3

OS:
Android 16 / MagicOS 10

APP COMMIT:

LLAMA.CPP COMMIT:

MODEL:

QUANT:

CONTEXT:

THREADS:

BACKEND:

BATTERY START:

THERMAL START:

PROMPT:

RESULT
------

Load:

TTFT:

Prompt tok/s:

Generation tok/s:

Peak RAM:

Temperature:

Battery delta:

Errors:

Notes:
```

------------------------------------------------------------------------

# 51. Criterios para aceptar una optimización

Una modificación no se aceptará simplemente porque produzca más
tokens/s.

Evaluaremos:

``` text
PERFORMANCE
STABILITY
THERMALS
MEMORY
ENERGY
QUALITY
```

Ejemplo:

``` text
Cambio A

+20 % tok/s
+40 % temperatura
-25 % autonomía
crash después de 15 min
```

No sería necesariamente una mejora útil.

------------------------------------------------------------------------

# 52. Definición de éxito de la primera etapa

La etapa inicial termina cuando podamos afirmar con mediciones:

``` text
PrismLocal funciona correctamente en Honor 200.

Tenemos una configuración reproducible.

Conocemos:
- velocidad;
- memoria;
- temperatura;
- consumo;
- estabilidad.

Conocemos el rango de modelos práctico.

Tenemos baseline guardado.

Podemos comparar futuras optimizaciones.
```

Solo entonces comienza realmente la personalización.

------------------------------------------------------------------------

# 53. Roadmap resumido

``` text
PHASE 0
Entorno

PHASE 1
Clone upstream

PHASE 2
Build sin modificar

PHASE 3
Instalar Honor 200

PHASE 4
Smoke tests

PHASE 5
Baseline

PHASE 6
Benchmark hardware

PHASE 7
Benchmark modelos

PHASE 8
Identificar bottlenecks

PHASE 9
Instrumentación propia

PHASE 10
Optimización CPU

PHASE 11
Optimización GPU

PHASE 12
Context/KV tuning

PHASE 13
Model Manager mejorado

PHASE 14
RAG mejorado

PHASE 15
Citas/evidencia

PHASE 16
UI personalizada

PHASE 17
Perfiles de modelos

PHASE 18
Router automático

FUTURO
NPU / Hexagon
```

------------------------------------------------------------------------

# 54. Principio del proyecto

La regla técnica que guiará el desarrollo será:

> **No reemplazar una parte de PrismLocal hasta demostrar mediante
> pruebas que esa parte limita nuestro objetivo.**

PrismLocal nos proporciona la base.

El Honor 200 proporciona nuestro primer entorno real.

Los benchmarks decidirán qué modificar.

------------------------------------------------------------------------

# 55. Fuentes principales

-   PrismLocal --- repositorio base:
    https://github.com/gthgomez/PrismLocal
-   llama.cpp --- motor de inferencia:
    https://github.com/ggml-org/llama.cpp

La descripción de la arquitectura y funciones actuales de PrismLocal de
este documento se basa en el README y estructura pública del repositorio
consultados el **18 de septiembre de 2026**. Debido a que el proyecto
puede cambiar, antes de iniciar cada etapa importante se debe comprobar
el commit exacto utilizado y conservarlo en los resultados del
benchmark.

------------------------------------------------------------------------

## Próximo paso operativo

El baseline, la compilación, la instalación y las primeras pruebas de modelos
ya se completaron. Al cierre de la sesión del 19 de septiembre también quedaron
terminados Room, cambio de modelo sin perder el chat, reconstrucción del contexto
con el tokenizer activo y selección Normal/Thinking para Qwen3 híbrido.

El siguiente bloque, deliberadamente pendiente, es:

``` text
1. resumen acumulativo de conversaciones largas
2. memoria de largo plazo GLOBAL/CHAT con aislamiento explícito
3. extracción de memoria sugerida y confirmable
4. RAG conectado al chat y filtrado por colecciones
5. PDF textual por páginas y citas
```

Hasta implementar el aislamiento, la memoria automática no se considera parte
del producto base. RAG tampoco se anunciará como terminado aunque su almacén y
búsqueda ya existan.
