# YOLO training environment

This package targets 64-bit CPython 3.11 on Windows and pins PyTorch and
TorchVision to the official CUDA 13.0 wheel index. Recreate the environment
only from the committed lock:

```powershell
uv sync --frozen --all-groups
```

The normal Python suite verifies that the installed wheels carry the expected
`+cu130` build metadata. This remains portable to hosts without an NVIDIA GPU:
importing and testing the package does not claim that CUDA hardware is present.

On the designated training host, enable the stricter hardware contract. It
requires CUDA availability, the NVIDIA GeForce RTX 4070 Ti SUPER identity, and
compute capability 8.9:

```powershell
$env:PONTINHO_REQUIRE_CUDA_HOST = '1'
python -m pytest tests/test_environment.py -q
```

Stage 10 training must run only after that opt-in contract passes. A compatible
NVIDIA driver is sufficient for the wheel-bundled CUDA runtime; a separate
local CUDA toolkit or `nvcc` installation is not required.
