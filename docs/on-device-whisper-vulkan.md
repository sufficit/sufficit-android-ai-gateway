# Whisper On-Device com Vulkan

## Objetivo

Validar se o `Samsung Galaxy A51 (SM-A515F)` consegue executar um backend local de `whisper.cpp` com aceleração Vulkan, em vez de depender exclusivamente do `sufficit-services-whisper`.

## Resultado atual

Em `2026-03-17`, o experimento base ficou positivo:

- o aparelho expõe `android.hardware.vulkan.compute`
- o aparelho expõe `android.hardware.vulkan.level=1`
- o aparelho expõe `android.hardware.vulkan.version=4198400`
- o `whisper.cpp` compilou para `arm64-v8a` com backend `ggml-vulkan`

Artefatos produzidos:

- `Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03\src\libwhisper.so`
- `Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03\ggml\src\libggml.so`
- `Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03\ggml\src\libggml-base.so`
- `Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03\ggml\src\libggml-cpu.so`
- `Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03\ggml\src\ggml-vulkan\libggml-vulkan.so`

## O que bloqueou no caminho

O backend Vulkan do `ggml` não compila no Android só com o NDK cru. Dois pontos precisaram ser corrigidos:

1. `glslc`
   - o CMake do `ggml-vulkan` estava gerando o build com `Vulkan_GLSLC_EXECUTABLE-NOTFOUND`
   - o binário correto já existia no próprio NDK:
     - `Z:\Android\sdk\ndk\27.2.12479018\shader-tools\windows-x86_64\glslc.exe`

2. `vulkan.hpp`
   - o NDK fornece `vulkan.h`, mas não o header C++ `vulkan.hpp`
   - foi necessário usar os headers oficiais da Khronos em:
     - `Z:\Desenvolvimento\temp\Vulkan-Headers\include`

Além disso, o `ExternalProject` do gerador de shaders do `ggml-vulkan` precisava de `CMAKE_MAKE_PROGRAM` explícito no `host-toolchain.cmake`, senão o passo host quebrava com `CMake was unable to find a build program corresponding to "Ninja"`.

## Build validado

Configuração que funcionou:

```powershell
& 'Z:\Android\sdk\cmake\3.22.1\bin\cmake.exe' `
  -S 'Z:\Desenvolvimento\temp\whisper.cpp' `
  -B 'Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03' `
  -G Ninja `
  -DCMAKE_MAKE_PROGRAM='Z:\Android\sdk\cmake\3.22.1\bin\ninja.exe' `
  -DANDROID_ABI=arm64-v8a `
  -DANDROID_PLATFORM=android-29 `
  -DCMAKE_TOOLCHAIN_FILE='Z:\Android\sdk\ndk\27.2.12479018\build\cmake\android.toolchain.cmake' `
  -DGGML_VULKAN=ON `
  -DGGML_OPENMP=OFF `
  -DGGML_LTO=OFF `
  -DBUILD_SHARED_LIBS=ON `
  -DWHISPER_BUILD_EXAMPLES=OFF `
  -DWHISPER_BUILD_TESTS=OFF `
  -DVulkan_GLSLC_EXECUTABLE='Z:\Android\sdk\ndk\27.2.12479018\shader-tools\windows-x86_64\glslc.exe' `
  -DVulkan_INCLUDE_DIR='Z:\Desenvolvimento\temp\Vulkan-Headers\include' `
  -DCMAKE_CXX_FLAGS='-IZ:/Desenvolvimento/temp/Vulkan-Headers/include'
```

Build:

```powershell
& 'Z:\Android\sdk\cmake\3.22.1\bin\cmake.exe' `
  --build 'Z:\Desenvolvimento\temp\whisper-android-vulkan-build-03' `
  --config Release `
  -j 8
```

## Limites atuais

Esse experimento ainda não está integrado no app principal.

Hoje já foi provado:

- o A51 tem runtime Vulkan utilizável
- o `whisper.cpp` com `ggml-vulkan` compila para o device target

Ainda falta provar dentro do app:

- carregar as libs pelo APK
- inicializar `whisper_context` com `use_gpu = true`
- medir latência real com modelo pequeno
- verificar consumo térmico e estabilidade em captura contínua

## Próximo passo técnico

Integrar um caminho JNI mínimo no app para:

1. carregar as `.so` compiladas
2. abrir um modelo local pequeno
3. chamar `whisper_init_from_file_with_params(...)` com `use_gpu = true`
4. expor um teste de diagnóstico e benchmark na UI

## Decisão prática

O experimento justifica continuar.

Ainda não justifica substituir o fluxo remoto por padrão. Primeiro precisamos medir:

- tempo por transcrição curta
- temperatura do aparelho
- impacto na bateria
- estabilidade do backend Vulkan no A51 em uso contínuo
