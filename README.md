# Inventario Biblioteca APK

Proyecto Android nativo para compilar un APK instalable.

## Qué incluye

- Captura por área de biblioteca:
  - Jefatura de Biblioteca y Colección de Reserva
  - Consulta y Tesis
  - Primer Nivel
- Captura por batería, estante y charola.
- Batería 1: estantes 1 a 8.
- Batería 2: estantes 1 a 5.
- Charolas 1 a 6 por estante.
- Escáner con cámara nativa Android usando CameraX + ML Kit Barcode Scanning.
- Pitido al registrar un código.
- Prevención de duplicados.
- Borrado individual con X.
- Exportación CSV compatible con Excel.
- Permiso nativo de cámara mediante `android.permission.CAMERA`.

## Cómo compilar el APK en Android Studio

1. Abre Android Studio.
2. Selecciona **Open** y abre la carpeta `InventarioBibliotecaAPK`.
3. Espera a que Gradle sincronice dependencias.
4. Conecta tu celular Android o usa un emulador.
5. Pulsa **Run** para instalarlo directamente, o ve a:
   **Build > Build App Bundle(s) / APK(s) > Build APK(s)**.
6. El APK debug quedará normalmente en:
   `app/build/outputs/apk/debug/app-debug.apk`.

## Nota

Este proyecto evita el problema de Chrome con `content://`, porque la cámara se pide como permiso nativo de Android dentro de la app.


## Generar APK automáticamente con GitHub Actions

Este proyecto incluye `.github/workflows/build-apk.yml`.

Pasos:

1. Crear un repositorio en GitHub.
2. Subir todo el contenido de esta carpeta.
3. Entrar a la pestaña **Actions**.
4. Ejecutar **Build Android APK**.
5. Descargar el artefacto **InventarioBiblioteca-debug-apk**.
6. Instalar el `app-debug.apk` en Android.

El APK debug solicitará permiso de cámara al abrir el escáner.
