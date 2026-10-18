# Instalaci?n y pruebas de modelos ? Honor 200

Inicio: 2026-10-17. Base: `1405a8e`. Dispositivo: ELI-NX9 / Android 16.

Paquete real: `com.prismai.llmhost.dev.debug`. Se conservan modelos y conversaciones existentes. Descargas y transferencias secuenciales; pesos y evidencias privadas en `build/model-install/`, excluidos de Git.

## Archivos solicitados y procedencia

| Clave | Archivo | Bytes | SHA-256 publicado |
|---|---|---:|---|
| A | Phi-3.5-mini-instruct_Uncensored-Q4_K_M.gguf | 2393232448 | `9ad0e44f9441c0ffb4adc8f3bd3a9ba3d662aaa82f617a6dfbff6d4ae31a946d` |
| E | realisticVisionV60B1_v51HyperVAE-Q4_0.gguf | 1570410496 | `04b3a83ff7511dca57e4733d7093f3f5eefd6dc88b1b44ed97279119ccecfa26` |
| B | p-e-w_Qwen3-4B-Instruct-2507-heretic-Q4_K_M.gguf | 2497279424 | `d4d7fcd11d36495fa340f91b514ae3a47f5da21cf27c158d620d736e3bb35a61` |
| D | Deliberate_v3.safetensors | 2132626832 | `aadddd3d7579de79db91f4ac03f2fbad4e9b71216bbebb50c338cae74b77cb27` |
| C | Qwen2.5-3B-Instruct-abliterated.Q4_K_M.gguf | 1929903520 | `c771d34ef5ad81f3a2ffb44a747c61de35b7ea2cc1fa522d9613e872a6dfe9eb` |
| F | DreamShaper8_LCM.safetensors | 2133804992 | `a4f3e1526c5dc4fcbe342f5c410d83ae202c7a415fcefcbb92e0f93fcd0a87c3` |

C usa la alternativa autorizada por el usuario: [mradermacher/Qwen2.5-3B-Instruct-abliterated-GGUF](https://huggingface.co/mradermacher/Qwen2.5-3B-Instruct-abliterated-GGUF), modelo base `huihui-ai/Qwen2.5-3B-Instruct-abliterated`. El archivo exacto Q4_K_M no estaba disponible en el repositorio tensorblock indicado.

Los repositorios, revisiones inmutables y metadatos LFS est?n en `scripts/models/requested-models.json`. Los estados por archivo distinguen descarga, verificaci?n SHA-256, transferencia, reconocimiento e inferencia.

## Implementaci?n y validaci?n en curso

- Se reutiliza ModelStorageManager y su manifiesto versionado. Los tipos TEXT/IMAGE provienen de metadatos y estructura de tensores; no del nombre del archivo.
- La importaci?n conserva la extensi?n Safetensors, valida encabezado/offsets acotados y admite tensores escalares. Perfiles persistidos: Euler a / CFG 7 / 16 pasos para D/E; LCM / CFG 1 / 4 pasos para F.
- Selecci?n de im?genes separada de texto; al generar imagen se liberan los pesos LLM y se conserva su selecci?n para recargarlos en el siguiente turno de texto.
- A descargado, transferido, reconocido y probado: 192 tokens en espa?ol, ARM KleidiAI, ~6.25 tokens/s, PSS ~2.87 GB; descarga/recarga y selecci?n desde el servicio aprobadas (46.456 s). Salida acotada por MAX_TOKENS; no se afirma calidad factual.
- E descargado y transferido, hashes comprobados; prueba de imagen 512?512 / 16 pasos / CFG 7 en curso. Backend CPU confirmado en el log nativo. No se marca inferencia aprobada antes de verificar el PNG.
- B/D/C/F pendientes de cerrar seg?n sus estados y resultados reales.

## Reproducci?n

### Prueba OpenCL autorizada

Los seis archivos ya se descargaron, transfirieron y verificaron mediante SHA-256 en el telefono. Eso no equivale a seis inferencias aprobadas.

La primera prueba de Realistic Vision en CPU, 512x512, Euler a, 16 pasos y CFG 7 se interrumpio de forma controlada al alcanzar el estado termico Android 3 (SEVERE). No produjo un PNG validado. El mensaje `Process crashed` de esa instrumentacion corresponde al `am force-stop` deliberado; no demuestra un fallo espontaneo de JNI. Evidencia local: `build/model-install/E-thermal-abort.json` y `E-native-live.txt`.

El usuario autorizo probar el backend OpenCL existente. El driver `/vendor/lib64/libOpenCL.so` esta presente y el manifiesto ya declara su acceso. Se corrige el cargador para evitar resolver su propio shim `libOpenCL.so`. La compilacion experimental se activa con `-PLLMHOST_DEBUG_OPENCL=true`; los valores normales de Debug/Release siguen en CPU. El texto conserva GPU layers = 0. La carga de imagen experimental exige el dispositivo `GPUOpenCL` y rechaza sustituirlo silenciosamente por CPU. Disponibilidad, carga y generacion todavia deben comprobarse en el telefono.

El servicio cancela imagenes al alcanzar el estado termico SEVERE y la instrumentacion vigila el mismo limite. La prueba inicial de un paso es diagnostica; no sustituye la validacion solicitada de 16 pasos.

Desde `PrismLocal/`:

```powershell
python scripts/models/download-requested.py A
python scripts/models/install-requested.py A
.\scripts\device\test-instrumentation.ps1 -Class com.prismai.llmhost.RequestedModelDeviceTest -ExpectedTests 1 -ModelId Phi-3.5-mini-instruct_Uncensored-Q4_K_M
```

Repetir en orden A ? E ? B ? D ? C ? F. El test requiere el APK DevDebug y su APK androidTest actuales. Los resultados neutrales se conservan en el chat ?Pruebas de modelos locales? para revisar texto e im?genes sin modificar las conversaciones originales.

La primera copia por stdin de adb shell en Windows se trunc? y se rechaz? por hash; su staging fallido se elimin? espec?ficamente. La transferencia actual utiliza adb push binario y copia por streaming dentro del tel?fono, verifica SHA-256 y promueve un directorio nuevo. No sobrescribe modelos existentes.
