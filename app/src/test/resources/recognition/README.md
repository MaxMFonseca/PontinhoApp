# Recognition test fixture provenance

All fixtures are project-generated, contain no photographed card artwork or personal data, and are dedicated
to the public domain under CC0-1.0. The binary fixtures are committed as Base64 so their provenance remains
patch-reviewable:

| Resource | Generator | SHA-256 of decoded bytes |
|---|---|---|
| `generated-128x64.png.b64` | .NET `System.Drawing.Bitmap`, solid lime, PNG encoder | `d318a00c8f0eb12f1a98faeb548a358161d5aea473610f5831df38cee6ce2e6d` |
| `generated-64x32-mirror.jpg.b64` | .NET red/blue bitmap, JPEG encoder; generated TIFF/EXIF orientation 2 APP1 segment | `29c31d51995e119ec5f5f755357af08dde3a5272196e8a0a89fa6947da8c28dd` |
| `generated-64x32-rotate90.jpg.b64` | Same generated JPEG; generated TIFF/EXIF orientation 6 APP1 segment | `897df3b2cdcaaf60f6773c3499d0f2c44b5362e3bdb0a2a963e5b6b181ca8c7d` |
| `malformed.bin.b64` | Truncated generated PNG signature/IHDR prefix | `02a3e298f1533f62558c58e4c70edcab9af5a50d62d925fd5390942020fb0fb8` |

The in-memory numbered pixel grids additionally make all eight EXIF transforms independently auditable.

No fixture is copied into the application package.
