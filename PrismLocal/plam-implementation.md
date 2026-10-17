# Plan de implementación — estabilidad de texto e imágenes

Fecha de inicio: 2026-10-17. Base: `30668d6`.

## Alcance y regla de avance

Ejecutar el bloque 1 acordado antes de ampliar RAG, VLM o compatibilidad de
difusión. Cada paso se implementa, se prueba y se documenta antes de comenzar
el siguiente. “Cerrado” significa que cumple los criterios indicados con
evidencia actual; no significa ausencia absoluta de errores futuros.

Las pruebas en el Honor 200 usarán procesos o paquetes de prueba aislados.
No borrar datos, chats ni modelos de la aplicación instalada. Los artefactos
generados y logs locales se guardan en `build/` o `validation/`, fuera de Git.
No publicar cambios. Tras completar el bloque 1, el usuario autorizó crear un
commit local antes de continuar al siguiente bloque.

## Bloque 1 — correcciones autorizadas

| Paso | Trabajo | Criterio para cerrar | Estado |
|---|---|---|---|
| 1.1 | Reparar el contrato JNI/R8 de difusión (A13) | Reproducir fallo previo; preservar clase/constructor; compilar Debug, Benchmark y Play Release; ejecutar retorno JNI del APK minificado en teléfono; verificar alineación | CERRADO |
| 1.2 | Proteger la vida de los motores LLM/difusión (A01, A08) | Ninguna llamada usa handles liberados; cierre espera trabajo activo fuera de main; pruebas de carreras/cierre y regresión de texto en teléfono | CERRADO |
| 1.3 | Entregar todo el texto bajo backpressure (A12) | Consumidor lento recibe exactamente todo el texto y un terminal; cancelación sigue respondiendo; pruebas JVM/dispositivo | CERRADO |
| 1.4 | Coordinar y cancelar generación de imágenes (A02) | Stop llega al motor; texto/imagen/resumen no compiten; estado/notificación tienen propietario; recuperación y pruebas reales de imagen y texto en teléfono | CERRADO |
| 1.5 | Corregir lint y activar CI (A03, A04) | Lint sin errores; workflow en raíz con directorios correctos; gates JVM, nativos y APK minificado reproducibles | CERRADO |

Las correcciones que sean dependencias estrictas de un paso se incluyen y
explican en ese paso. No se marcan como aprobadas pruebas no ejecutadas.
Una limitación real de dispositivo/modelo se registra y se resuelve antes de
declarar cerrado el paso que depende de ella.

## Fases siguientes — previstas, fuera del bloque actual

2. Presupuesto compartido de memoria y descarga/recuperación bajo presión.
3. RAG: colecciones por chat, embeddings estables/versionados, ingesta acotada,
   recuperación conectada a ContextBuilder y pruebas de aislamiento/calidad.
4. PDF por páginas, citas y modo estricto; OCR posteriormente.
5. Perfiles explícitos de difusión: SDXS, Turbo y otros según resultados reales.
6. VLM: lectura de imágenes con arquitectura/proyector compatible y RAM acotada.
7. Recuperación de trabajos/descargas, edición/regeneración y mejoras de UI.

## Evidencia y decisiones

### 1.1 — JNI/R8 de difusión

- Hallazgo de partida: el APK Dev Benchmark de la auditoría conserva el nombre
  de `DiffusionNativeResult`, pero su DEX no contiene el constructor requerido
  por `diffusion_jni.cpp`.
- La aplicación instalada en el teléfono es Dev Debug. Para probar el APK
  minificado sin tocarla se ejecutará un pequeño harness mediante `app_process`
  que carga el APK y sus bibliotecas en un proceso shell aislado. El harness
  se compila aparte y no altera las decisiones R8 del APK que está probando.
- Reproducción en Honor 200 / Android 16: `NoSuchMethodError` en
  `NativeDiffusionBridge.nativeGenerate`, constructor
  `(ZIIILjava/lang/String;[BLjava/lang/String;F)V`. Evidencia local:
  `build/verification/step-1.1/before-device-jni.txt`.
- Corrección: reglas explícitas para el puente y el carrier de difusión en
  `app/proguard-rules.pro`.
- Debug y Benchmark recompilan; suites JVM Dev/Play aprobadas (256 por flavor,
  reutilizadas por Gradle porque no cambió código JVM).
- Benchmark corregido, prueba en teléfono: **PASS**, 10 retornos JNI nativos
  sin modelo y constructor/campos del resultado exitoso. Script reproducible:
  `scripts/device/test-minified-jni.ps1 -Apk <apk>`.
- Play Release se verifica con `-PLLMHOST_SUPABASE_ANON_KEY=ci-public-build-placeholder`,
  el mismo valor de prueba de CI. APK sin firma de distribución; no valida
  autenticación ni se publica. Compilación terminada correctamente (1 min 49 s).
- Play Release corregido probado en el mismo teléfono: **PASS**, 10 retornos
  JNI y constructor/campos. Debug, Benchmark y Play Release pasan `zipalign`
  con alineación de 16 KB. Se conservaron los hashes de los APK verificados en
  `build/verification/step-1.1/apk-hashes.txt`.
- **Cierre 1.1:** criterios cumplidos. No se afirma generación de imagen real
  todavía: este punto valida el contrato binario JNI del resultado. La prueba
  de imagen real y Stop pertenece al punto 1.4.

### 1.2 — vida de motores nativos

- Inicio después del cierre de 1.1. Inspección de propiedad de handles,
  sincronización de contexto y cierre del servicio antes de implementar.
- Implementado registro de handles con leases compartidos en JNI: retirada
  atómica, rechazo de IDs antiguos y liberación tras la última llamada activa.
- Pruebas C++ ejecutadas en teléfono: los seis ejecutables pasan, incluido
  retiro concurrente, rechazo de reutilización de IDs y 200 carreras controladas.
- Primer smoke integrado: 100 carreras JNI LLM, 100 difusión y generación real
  de 32 tokens pasan. La prueba SDXS activa aborta antes de cancelar.
- Dependencia descubierta al probar SDXS: Stable Diffusion compila con
  `GGML_MAX_NAME=160`, mientras GGML/llama/JNI usaban 64 pese a compartir
  `ggml_tensor`. Se alinea la definición en CMake para eliminar la incompatibilidad
  de ABI antes de repetir la prueba. El paso 1.2 sigue abierto.
- Tras corregir la ABI, el smoke Debug pasa completo en el teléfono: carreras
  de ambos motores, texto real, cierre durante SDXS (latencia de cierre 0 ms),
  recarga y generación real de PNG (313,257 bytes). Sin instalar ni cambiar la
  app del usuario. Evidencia: `build/verification/step-1.2/device-lifecycle-debug.txt`.
- El cierre del servicio cancela difusión y espera/libera ambos motores desde
  `teardownScope`; se retiró la liberación de difusión en el hilo principal.
- Pendiente: repetir el contrato/estrés en Benchmark minificado y verificar
  Play Release con las nuevas fuentes nativas antes de cerrar el paso.
- **Cierre 1.2:** Benchmark pasa el smoke completo con las mismas 100 carreras
  por motor, texto real, cierre activo 0 ms y PNG de 313,257 bytes tras recargar.
  Play Release compila y pasa 10 retornos JNI. Los tres APK conservan alineación
  de 16 KB y las suites JVM Dev/Play pasan. No se modificó código vendorizado.

### 1.3 — entrega completa del texto

- Inicio después del cierre de 1.2. Se sustituirá el descarte tras 100 ms por
  suspensión cancelable con memoria acotada. Se verificará comparación exacta
  de salida con un consumidor lento y cancelación mientras el canal está lleno.
- Reproducción antes de corregir: fallan los tres casos nuevos en teléfono.
  GGUF real: 183 tokens rápidos frente a 54 recibidos por el consumidor lento.
  Ring controlado: 513 de 1000. Cancelar con `genMutex` ocupado omite la limpieza.
  Evidencia: `build/verification/step-1.3/before-device.txt`.
- Antes de instalar mediante actualización `-r`, se guardó backup local privado
  de DB, chats y preferencias en `validation/` (8 archivos, 554,496 bytes).
  No se desinstaló la aplicación ni se borraron datos.
- Corrección: `send()` cancelable en el canal acotado y cleanup nativo bajo
  `NonCancellable`. Pendiente repetir las pruebas y cerrar.
- **Cierre 1.3:** los 3 tests de dispositivo pasan (6.527 s): texto GGUF idéntico
  con consumidor rápido/lento, 1000 tokens en orden y un terminal, cancelación
  con mutex ocupado, ring vacío y reinicio. El contrato nativo conserva CANCELLED
  tras retirar un worker; no exige TOMBSTONED en ese caso. Las suites JVM
  Dev/Play pasan. Los 8 archivos originales de DB/chats/preferencias coinciden
  byte a byte con el backup.

### 1.4 — coordinación y Stop de imágenes

- Inicio después del cierre de 1.3. Integrar comando y herramienta de imágenes
  con las sesiones, el job activo, la notificación y la cancelación del servicio.
- Implementado job de imágenes compartido con el coordinador del servicio,
  retiro del resumen previo, reemplazo serializado y limpieza por sesión.
  El agente propaga cancelación sin iniciar un follow-up.
- Primera prueba completa detectó Stop tardío dentro de un paso UNet (>15 s).
  Se conectó el callback público de evaluación de grafos de difusión para
  abortar entre nodos y se limpia su userdata al salir. Sin cambios en terceros.
- Al abortar CLIP se encontró una aserción en el conditioner vendorizado.
  Se cambian únicamente sus comprobaciones de resultado vacío para propagar
  una condición vacía que la pipeline ya maneja, en lugar de SIGABRT. Esto es
  una dependencia de Stop seguro para SDXS/CLIP, no una ampliación de modelos.
  El crash de la prueba se recuperó restaurando el backup previo y retirando
  únicamente su chat/modelo temporal. Revalidación pendiente.

## Continuación y validación del paso 1.4

Se reanudó el trabajo con autorización adicional del usuario. La compilación
Debug con el ajuste de CLIP terminó correctamente y se instaló mediante
actualización `adb install -r`, conservando los datos.

`ImageOperationServiceTest` pasó en el Honor 200: **1 test, 46.255 s**.
Comprueba cancelación y notificación, reemplazo de imagen por imagen, comando
en mayúsculas, PNG real de 512×512, recuperación de texto y cancelación de la
herramienta del agente sin follow-up. Las 3 regresiones de backpressure también
pasan (6.404 s). Evidencia en `build/verification/step-1.4/`.

El log confirma abortos limpios de CLIP y generación real terminada en 41.22 s.
Se amplió la espera del caso del agente para comprobar también Stop dentro de
UNet, además de la cancelación durante CLIP; esta repetición está en curso.

- **Cierre 1.4:** repetición ampliada aprobada (52.734 s), con aborto de UNet
  confirmado en logcat y Stop de CLIP en 168 ms. Benchmark minificado pasa
  100 carreras por motor, texto real, cierre activo en 0 ms y PNG de 313,257
  bytes después de recargar. Play Release compila y pasa el contrato JNI x10.
  Ambos APK pasan alineación de 16 KB; las suites JVM Dev/Play pasan.
- Tras limpiar los fixtures se restauraron y verificaron byte a byte los 8
  archivos originales de DB/chats/preferencias. Los modelos originales se
  conservaron y no quedaron modelos temporales. Evidencia de conservación:
  `build/verification/step-1.4/user-data-check.txt`.
- Validación de difusión limitada a SDXS/CLIP sobre CPU en Honor 200 / Android
  16; no demuestra soporte GPU ni otros modelos. El callback de aborto se
  verifica en este backend; no se afirma latencia universal entre operadores.

### 1.5 — lint y CI

Inicio después del cierre verificado de 1.4. Corregir el recurso API 27 y
ubicar el workflow en la raíz real, manteniendo los gates reproducibles.

- `AppTheme.Base` conserva los atributos comunes; `values-v27/styles.xml`
  contiene `windowLightNavigationBar`. Lint Dev/Play: **0 errores, 44 avisos**
  por flavor; los avisos siguen visibles, sin baseline que los oculte.
- Se retira el override `ANDROID_PLATFORM=android-29`: AGP proporciona API 26
  desde `minSdk`. Confirmado en CMakeCache y compilación Debug. El APK final
  pasa imagen real/Stop CLIP y UNet/recuperación (45.209 s) y las 3 pruebas de
  texto (6.418 s). Minificados finales también verificados. Esto no demuestra
  ejecución en un teléfono Android 8.
- Workflow trasladado a `.github/workflows/android-ci.yml` de la raíz Git,
  directorio `PrismLocal`, lint y JVM Dev/Play, C++ con sanitizers, Debug y
  minificados secuenciales, comprobación JNI del DEX final y alineación 16 KB.
  YAML y hashes de las acciones existentes verificados. No se publica ni se
  afirma una ejecución remota de Actions: requiere subir los cambios.
- Comprobador JNI probado contra ambos APK y contra un fixture al que se
  retiró `class_data`, manteniendo referencias de métodos: rechaza el constructor
  ausente. Pruebas C++ del teléfono: 6 ejecutables aprobados, incluidas las
  200 carreras del registro de handles. Suites JVM: 256 tests por flavor.
- Para la última instalación se guardó un respaldo nuevo del estado actual
  (9 archivos), conservando también cambios posteriores al respaldo inicial.
  Se compararon los chats y mensajes Room antes de restaurar para evitar
  sobrescribir cambios ajenos al fixture: coinciden. Los 9 archivos quedaron
  idénticos byte a byte y no hay modelos temporales.
- La comprobación detectó un JSON residual del chat de prueba ya eliminado en
  Room. Se verificaron sus marcadores exclusivos y se retiró únicamente ese
  archivo. `ChatManager.persistSnapshotJsonBackup()` puede escribir snapshots
  antiguos después de `deleteChat()`; queda pendiente serializar esa limpieza
  en la fase de persistencia/recuperación. No se observó resurrección en Room.
- Se corrigió el wrapper Windows PowerShell: el stderr informativo de ADB al
  redirigir logs no se interpreta como fallo; se comprueba el exit code real.
  La repetición completa de `scripts/verify.ps1 -UseCiAuthPlaceholder
  -NativeTestsOnDevice` termina **correctamente**, incluidos los 6 ejecutables
  C++ en el teléfono. Log: `build/verification/step-1.5/full-gate-final.txt`.
- **Cierre 1.5:** Debug, Benchmark y Play Release compilan con mínimo nativo
  API 26. Lint Dev/Play sin errores; 256 pruebas JVM por flavor aprobadas.
  Contratos DEX y alineación APK 16 KB pasan. Las 12 bibliotecas ELF de los
  tres APK también pasan comprobación de segmentos LOAD a 16 KB; hashes en
  `build/verification/step-1.5/final-apk-evidence.json`.
- Benchmark final en teléfono: 100 carreras por motor, 32 tokens reales,
  cierre activo 0 ms y PNG de 313,257 bytes después de recargar. Play Release:
  contrato JNI nativo x10 aprobado. Pruebas reales de imágenes limitadas a
  SDXS/CLIP en CPU; Play sigue siendo un APK de verificación sin firma de
  distribución, con placeholder público de CI, sin validar autenticación.
- Teléfono: Dev Debug final instalado con `-r`; arranque en frío aprobado
  (1.217 s). Se conservó el estado del respaldo reciente y los modelos originales.

## Resultado del bloque 1

Los pasos 1.1–1.5 cumplen sus criterios y quedan cerrados en orden. El workflow
está preparado en la raíz, sin ejecución remota de Actions ni publicación de
cambios. RAG, VLM, presupuesto compartido de memoria, otros modelos de difusión
y el residuo JSON descrito siguen pendientes en las fases siguientes.

El bloque 1 se guarda en un commit local por petición del usuario. No hay
publicaciones. Los logs y backups privados están en `build/verification/` y
`validation/`, excluidos de Git.
