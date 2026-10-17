# Pruebas en dispositivo sin alterar la aplicación instalada

Desde `PrismLocal/`, con JDK 17, Android SDK y un dispositivo ADB autorizado:

```powershell
.\scripts\device\test-minified-jni.ps1 -Apk app/build/outputs/apk/dev/benchmark/app-dev-benchmark.apk
```

Usar `-Serial` si hay varios dispositivos y `-Sdk` si el SDK no está en
`ANDROID_HOME` ni en la ubicación predeterminada de Windows.

El script compila un harness Java independiente y lo ejecuta con `app_process`
en un directorio temporal del teléfono. Carga el **APK final** con
`DexClassLoader`: las referencias del harness no pueden impedir que R8 elimine
código de la aplicación y así ocultar la regresión.

Verifica que el retorno real de difusión JNI puede construir su carrier
(10 veces sin modelo), y que constructor/campos de un resultado exitoso
conservan su contrato. No es una prueba de calidad de imágenes ni de carga de
modelos. Un fallo termina con código distinto de cero.

El modo predeterminado no instala/desinstala paquetes ni accede a datos de usuario. Solo elimina el
directorio remoto recién creado por esa ejecución. Los artefactos locales
quedan bajo `build/jni-smoke/`, excluido de Git. Puede probar un APK unsigned.

## Vida de motores

```powershell
.\scripts\device\test-native-runtime.ps1
.\scripts\device\test-minified-jni.ps1 -Apk app/build/outputs/apk/dev/debug/app-dev-debug.apk `
    -Harness NativeLifecycleSmoke -LlmModel build/test-models/tinyllama-v0.q8_0.gguf `
    -ModelsPackage com.prismai.llmhost.dev.debug `
    -DiffusionModelRelativePath files/models/sdxs-512-tinySDdistilled_Q8_0/versions/v1/model.gguf
```

El primer script compila las pruebas portables C++ para el ABI del teléfono y
las ejecuta allí. No necesita modelos.

El segundo ejecuta carreras reales JNI, genera texto con el GGUF pequeño y
cierra difusión durante una generación SDXS activa; después vuelve a cargar
el modelo y verifica una imagen PNG. En este modo se copia **solo el modelo
seleccionado** de una aplicación debuggable al directorio temporal mediante
`run-as`. No se modifican su modelo, preferencias, chats ni imágenes. La copia
temporal se elimina al finalizar. No acepta rutas fuera de `files/models/`.

El GGUF pequeño usado por las pruebas históricas es `afrideva/TinyLLama-v0-GGUF`,
archivo `tinyllama-v0.q8_0.gguf`, 6,750,304 bytes, SHA-256
`0cbe6769faaa77f4cdbf2f39dbc5f0fb9b3d62f7f9bc5760aa364946e6f2d0f5`.
Los modelos no se incluyen en Git ni se descargan automáticamente por los scripts.

## Instrumentación

Después de compilar e instalar el APK y su APK de pruebas con `adb install -r`:

```powershell
.\scripts\device\test-instrumentation.ps1 -Class com.prismai.llmhost.BridgeBackpressureTest -ExpectedTests 3
```

Este wrapper exige el número esperado de pruebas aprobadas y rechaza errores,
crashes y pruebas omitidas. `adb shell am instrument` por sí solo puede devolver
código 0 aunque JUnit haya fallado. El wrapper no instala ni borra aplicaciones.

`ImageOperationServiceTest` requiere SDXS ya instalado y el asset GGUF pequeño.
Crea su propio chat/modelo, verifica Stop durante CLIP/UNet, reemplazo por otra
imagen y por texto, notificación y cancelación del agente. Limpia sus fixtures
y restaura selección de chat/modelo/settings al finalizar normalmente. Antes de
ejecutarlo sobre una instalación con datos, guardar un respaldo privado de
DB/chats/preferencias: un crash nativo impide ejecutar el bloque `finally`.

```powershell
.\scripts\device\test-instrumentation.ps1 -Class com.prismai.llmhost.ImageOperationServiceTest -ExpectedTests 1
```

## Gate local y CI

Desde `PrismLocal/`, con Python 3 y las herramientas Android fijadas en Gradle:

```powershell
.\scripts\verify.ps1 -UseCiAuthPlaceholder -NativeTestsOnDevice
```

Ejecuta lint y JVM de ambos flavors, Debug, Benchmark y Play Release por
separado, contratos JNI del DEX final, alineación APK 16 KB y C++ en ADB.
El placeholder permite compilar Play sin autenticar contra un servicio; ese
APK no es una distribución validada. Omitirlo si se proporciona configuración
real de Play. El gate no instala APK ni ejecuta pruebas de generación reales.

CI vive en `<raíz Git>/.github/workflows/android-ci.yml` y usa `PrismLocal/`
como directorio de trabajo. Añade C++ con sanitizers en Linux. La generación
real y la cancelación requieren las pruebas de teléfono anteriores. El
comprobador `scripts/check-apk-jni.py <apk> [<apk> ...]` usa únicamente la
biblioteca estándar y valida definiciones, no solo referencias DEX.
