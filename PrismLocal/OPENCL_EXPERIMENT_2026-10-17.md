# OpenCL experimental: diagnostico y validacion

Base: `1405a8ed7eb328e4c53651da9d00361f6d29b3f3`. Rama: `experiment/adreno-opencl-sdxs`.
Los cambios locales del bloque de instalacion de modelos se conservaron al crear la rama; no son commits nuevos. No se ha publicado nada.

## Diagnostico antes de ampliar la implementacion

- `on_gpu` originalmente no elegia un dispositivo: solo se registraba en el log. La API vendorizada acepta asignaciones por modulo mediante `backend` y `params_backend`; `sd_list_devices` enumera identificadores reales.
- Debug, Benchmark y Release desactivan OpenCL; Adreno lo habilita. El experimento anterior uso temporalmente un opt-in Debug, autorizado en la conversacion. El nuevo bloque usara exclusivamente el paquete Adreno separado; se retirara ese opt-in y se restaurara el APK CPU habitual sin borrar sus datos.
- CMake comparte GGML entre llama y difusion y enlaza un shim propio llamado `libOpenCL.so`. Abrir primero ese mismo nombre permite autocarga. Se priorizo la ruta del fabricante y se comprobo que su entrada no fuera el wrapper. La prueba real cargo `/vendor/lib64/libOpenCL.so` desde el proceso Android.
- Realistic Vision cargo los parametros UNet en OpenCL (1272.85 MB); Android observo aproximadamente 1.16 GB de memoria grafica y GPU ocupada. Esto NO prueba una imagen terminada. El primer grafo UNet no termino durante el intento diagnostico, detenido expresamente para investigar la ruta de convolucion. No se calcula speedup con ese intento.
- Los kernels locales incluyen MUL_MAT, IM2COL, CONV_2D F16/F32, normalizacion, activaciones y otras operaciones requeridas por SD. Hay una convolucion directa con teselas para Qualcomm; su existencia no demuestra rendimiento ni compatibilidad de un grafo completo.
- El manifiesto declara acceso opcional a `libOpenCL.so`. No se requiere root ni copiar bibliotecas Qualcomm. La identidad exacta de la GPU, limites de asignacion y version del controlador aun deben consultarse dentro del APK experimental; no se asume Adreno 720.
- La API vendorizada admite CLIP/TE CPU, difusion GPU y VAE CPU. Es una combinacion a probar, no una mejora medida. `auto_fit` puede modificar asignaciones: se dejara desactivado en mediciones explicitas para evitar fallback oculto.

## Referencias examinadas

- [Local Diffusion](https://github.com/rmatif/Local-Diffusion), revision `184b7f92cf2f810e7d5eb4b04b190a5da829005f`: Apache-2.0; Android incluye una biblioteca OpenCL precompilada. Su README describe OpenCL experimental para Adreno 7xx y posible fallback. No se importan sus binarios ni su ABI.
- [stable-diffusion.cpp](https://github.com/leejet/stable-diffusion.cpp), referencia publica `f0aac524d3f29140e60ba78f3cfb16d6acea87db`: MIT. Se usa la API del encabezado local, no la de master por suposicion. Sus arboles y dependencias no se sustituyen en este bloque.
- [GGML OpenCL](https://github.com/ggml-org/llama.cpp/blob/master/docs/backend/OPENCL.md), referencia publica `797de63b2fa7396dcc8caca76834c699ac84e1c3`: MIT. Kernels Adreno y tipos cuantizados no equivalen a compatibilidad medida con este dispositivo o con SD.
- [Khronos ICD Loader](https://github.com/KhronosGroup/OpenCL-ICD-Loader), revision `5192c84f8059e5f703e5452929b613f9487f6e4c`: Apache-2.0. Es un despachador, no el controlador GPU. Sirve de referencia; no se instala un ICD de escritorio ni se cambia la configuracion del sistema Android.

Los SHA anteriores identifican referencias publicas consultadas, NO revisiones comprobadas de las copias vendorizadas locales. Estas copias son directorios del repositorio principal; `git -C ... rev-parse HEAD` devuelve el HEAD principal y no acredita su version upstream.

## Secuencia obligatoria

1. APK Adreno aislado, arm64, R8/JNI y alineacion 16 KB; restaurar CPU habitual sin desinstalar ni borrar datos.
2. Runtime: plataformas, fabricante, dispositivo, tipo, versiones, memoria global/maxima y dispositivos GGML, con errores explicitos.
3. GGML: matriz pequena sobre GPU explicita y CPU, igualdad numerica y backend comprobado.
4. SDXS 512: mismo modelo, prompt del cubo rojo, seed 42, un paso y CFG 1 en CPU y GPU; comprobar PNG y tiempos reales.
5. Cancelacion, descarga/recarga y recuperacion CPU; vigilar temperatura y memoria. No ejecutar estres ilimitado.
6. Regresiones C++, JVM, variantes CPU, texto Phi original y Stop. Luego retomar los checkpoints grandes pendientes del bloque de modelos.

Estado actual: diagnostico inicial documentado; fases 2-6 pendientes del APK aislado. No se declara todavia `STABLE_DIFFUSION_GPU_VERIFICADO`.
