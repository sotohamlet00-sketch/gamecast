# GameCast: Mando HID, Juego en TV y Modo Consola (Shizuku)

App Android nativa (Kotlin, minSdk 28, compileSdk/targetSdk 36, AGP 8.9.1, Kotlin 2.0.21, Gradle 8.11.1).
Sin root, sin servicios en la nube, sin APIs de pago y **sin permiso de internet**. Todo open-source.

> ## ⚠️ No se puede publicar en Google Play
> El Modo Consola usa Shizuku, pantallas virtuales e inyección de entrada con permisos de shell, algo que la política
> de Google Play no admite. Se distribuye por **GitHub Releases** o **F-Droid**, instalando el APK a mano.

## Los tres modos

Cada modo se enciende y se apaga por separado desde la pantalla de inicio.

| Modo | Para qué | Cómo funciona | Requisitos |
|------|----------|---------------|------------|
| **Mando HID** | Usar el teléfono como mando Bluetooth de una TV, TV box o PC | `BluetoothHidDevice` (Bluetooth Classic) en un servicio en primer plano | Bluetooth con perfil HID Device |
| **Juego en TV (Miracast)** | Ver la pantalla del teléfono en la TV (espejo simple) | `MediaProjection` + `VirtualDisplay` sobre una ventana en la pantalla externa | TV conectada desde el panel Cast del sistema |
| **Modo Consola (Shizuku)** | Jugar un juego del teléfono viéndolo en la TV **con el mando táctil en el teléfono** | El juego corre en una pantalla virtual que va directa a la ventana de la TV; el mando es un overlay en la pantalla principal que inyecta toques en la pantalla virtual | Shizuku activo, Android 13 o superior recomendado |

### Cómo funciona el Modo Consola

1. `ConsoleService` abre una ventana en la TV con un `SurfaceView` y crea la pantalla virtual **«GameCast»** apuntando
   directamente a esa `Surface`: sin `MediaProjection`, sin capturas y sin latencia añadida.
2. La pantalla virtual se crea en el **servicio de usuario de Shizuku** (proceso shell) con las banderas
   `PUBLIC | OWN_CONTENT_ONLY | PRESENTATION` (más banderas de confianza en Android 13+). Resolución: la del teléfono
   limitada a 1920 en el lado largo, en horizontal, con la densidad del teléfono.
3. El juego se lanza en ella (`am start --display`, equivalente a `ActivityOptions.setLaunchDisplayId` con
   permisos de shell; si no hay servicio de usuario se prueba `ActivityOptions` desde la app). Después se **comprueba en
   qué pantalla quedó** y se avisa si el juego no es compatible.
4. `FloatingPadService` dibuja el mando (`TYPE_APPLICATION_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL`) en la
   pantalla principal. Cada pulsación llama a `TouchInjectorShizuku` (`tap`, `holdDown`, `moveTo`, `release`), que inyecta
   `MotionEvent` multitáctiles **con el `displayId` de la pantalla virtual**. El mando no sale en la TV porque la TV
   recibe la pantalla virtual, no la principal.
5. Cada juego tiene su mapa (`mapping_<paquete>.json`, coordenadas en porcentaje de la pantalla virtual), que se crea en
   `ControlMapperActivity`: se ve el juego en directo y se arrastran los puntos sobre sus botones reales.

Acceso a las APIs ocultas, por orden: (1) **UserService de Shizuku** (AIDL, proceso shell); (2) respaldo con
**`ShizukuBinderWrapper`** + `HiddenApiBypass` desde la app para la inyección de toques; (3) si todo falla, la app avisa
con el motivo y ofrece el espejo simple («Juego en TV»).

## Shizuku: requisitos y activación

Shizuku **no es root**: es un servicio que tú arrancas (por ADB inalámbrico) y que presta permisos de shell solo a las apps
que autorices. La app detecta el estado en vivo (no instalado, instalado sin arrancar, sin permiso, listo) y, si no está
listo, desactiva el Modo Consola con un mensaje claro y un botón **«Preparar Shizuku»** con la guía paso a paso.

* Guía oficial: <https://shizuku.rikka.app/>
* Resumen: instalar Shizuku, activar opciones de desarrollador y «Depuración inalámbrica» (Android 11+, con Wi-Fi), iniciar
  Shizuku por depuración inalámbrica con su asistente (en Android 13 se puede hacer desde el mismo teléfono), y conceder el
  permiso a GameCast. **Hay que repetirlo tras cada reinicio del teléfono.**
* Dependencias (verificadas en Maven Central): `dev.rikka.shizuku:api` y `provider` **13.1.5** (la última publicada, sep. 2023) y
  `org.lsposed.hiddenapibypass:hiddenapibypass:4.3` (solo para el respaldo).

## Limitaciones (honestas)

* **Los botones del propio juego SÍ se verán en la TV.** Shizuku no los elimina porque forman parte de lo que dibuja el
  juego. Lo único que desaparece es el mando de GameCast.
* **Algunos juegos no respetan la pantalla secundaria.** Los que fuerzan la pantalla principal, o que no son
  redimensionables, aparecerán en el teléfono. La app lo detecta y avisa de que ese juego no es compatible con el modo
  «mando separado». En Android 10 o superior, los juegos no redimensionables pueden requerir activar «Forzar actividades
  redimensionables» en opciones de desarrollador (no comprobado).
* **La inyección de toques depende de APIs ocultas** (`IInputManager.injectInputEvent`, `InputEvent.setDisplayId`,
  `DisplayManager` con banderas de confianza) cuyas firmas cambian entre Android 13, 14, 15 y 16. Se localizan por
  reflexión probando variantes; si no se encuentran, el motivo queda en el diagnóstico y se ofrece el espejo simple.
* **No he podido compilar ni ejecutar nada** en el entorno donde se generó el proyecto (sin Gradle, sin Android SDK y sin
  teléfono). El código se revisó a mano y con comprobaciones automáticas (llaves, imports, recursos, clases del manifiesto
  y métodos del AIDL). Es muy probable que el Modo Consola necesite una o dos rondas de ajustes con un teléfono real.
  Por eso **Preparar Shizuku > Probar conexión > Copiar diagnóstico** te da el motivo exacto de cada fallo para pegarlo aquí.
* La app no modifica APKs de terceros ni da instrucciones para ello.
* Solo puede haber una pantalla virtual a la vez: no se puede mapear controles con el Modo Consola en marcha.
* El audio del juego sigue saliendo por el teléfono.
* Contenido protegido (DRM, `FLAG_SECURE`) se ve en negro.

## Compilar con GitHub Actions y descargar el APK

El flujo `.github/workflows/build.yml` instala JDK 17, el SDK de Android y Gradle 8.11.1 (con caché), genera el wrapper,
ejecuta `./gradlew assembleDebug` y sube el APK como artefacto **`gamecast-apk`** (30 días). Se lanza en cada `push` a `main`
y a mano con «Run workflow». No hace falta ningún paso manual en la nube.

### 1. Subir el proyecto desde el celular (Termux)

1. Crea un repositorio vacío en github.com (sin README).
2. Crea un token: github.com > Settings > Developer settings > Personal access tokens. En un token clásico marca `repo`
   **y `workflow`** (sin `workflow` GitHub rechaza `.github/workflows`).
3. En Termux:
   ```bash
   pkg update && pkg install git unzip
   termux-setup-storage
   cd ~ && unzip /sdcard/Download/gamecast_consola.zip
   cd gamecast_consola
   git init -b main
   git config user.name "TU_USUARIO" && git config user.email "tu@correo"
   git add . && git commit -m "GameCast"
   git remote add origin https://github.com/TU_USUARIO/TU_REPO.git
   git push -u origin main          # usuario: tu usuario; contraseña: el TOKEN
   ```
   Alternativa: `pkg install gh`, `gh auth login` y `gh repo create gamecast --source=. --push`.

### 2. Pasar el APK al teléfono con Android 13 sin enviar archivos

No hace falta transferir nada entre teléfonos: **descarga el APK directamente en el teléfono con Android 13.**
1. En ese teléfono abre github.com en el navegador e inicia sesión (los artefactos de Actions solo se descargan con sesión).
2. Entra en tu repositorio > **Actions** > la última ejecución «Build GameCast APK» > **Artifacts** > `gamecast-apk`.
3. Descomprime el ZIP con el gestor de archivos y abre `app-debug.apk`. Permite «Instalar apps desconocidas» a esa app
   (Ajustes > Apps > Acceso especial) y, si el aviso de seguridad lo pide, instala igualmente.
4. Si Actions falla, abre el paso en rojo, copia el error y pásalo para corregirlo.

## Cómo probar el Modo Consola

1. Pulsa **Preparar Shizuku** en la tarjeta del Modo Consola y sigue los pasos hasta ver «Listo». Prueba con **Probar conexión**.
2. Conecta la TV desde el panel Cast del sistema (Ajustes > Dispositivos conectados > Preferencias de conexión > Transmitir,
   o el botón «Transmitir» del panel rápido) y comprueba que aparece al elegir la TV.
3. Pulsa **Iniciar Modo Consola**, elige un juego (los juegos salen primero). Si no tiene mapa, se abre el mapeador:
   usa **Jugar** para llegar a la pantalla de juego, **Colocar** para arrastrar cada punto sobre el botón real, y **Guardar**.
4. Se arranca la pantalla virtual, se lanza el juego en la TV y aparece el mando flotante en el teléfono (✥ lo arrastra,
   ✕ cierra todo y vuelve a GameCast).

## Cambios de API de Android 13 a 16 tenidos en cuenta

* **Android 13:** `POST_NOTIFICATIONS` en ejecución; banderas de pantalla virtual de confianza (`TRUSTED`, grupos de
  pantalla); `createWindowContext` para la ventana sobre la TV.
* **Android 14:** tipos de servicio en primer plano con permiso propio. Aquí `specialUse` (con su permiso y la propiedad
  `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`) en `ConsoleService` y `FloatingPadService`, porque `connectedDevice` falla sin
  permisos de Bluetooth/USB/red concedidos. `HidService` sigue siendo `connectedDevice` y `ProjectionService` `mediaProjection`.
* **Android 14 y 15:** MediaProjection (solo en el espejo simple): servicio antes de `getMediaProjection`, callback antes del
  `VirtualDisplay`, permiso de un solo uso y captura de pantalla completa. Android 15: parada al bloquear la pantalla.
* **Android 14 a 16:** la firma de `IInputManager.injectInputEvent` puede tener 2 o 3 parámetros; se detecta por reflexión.
* **Android 15 y 16:** edge-to-edge forzado (cada pantalla aplica insets) y bloqueo de orientación ignorado en pantallas grandes.

## Correcciones de esta versión

* `HidManager.start()`: si `registerApp()` fallaba y el usuario reintentaba, no pasaba nada porque `hidDevice` seguía no nulo.
  Ahora se vuelve a registrar (y volver a la app lo reintenta automáticamente).
* `MainActivity.onCaptureResult()`: si se concede la captura pero la TV ya no está conectada, muestra
  «La TV elegida no está conectada. Elígela de nuevo.»
* `ic_launcher.xml` e `ic_launcher_round.xml` están en `mipmap-anydpi-v26/`, no en `drawable/`.
* Sin restos del enfoque cancelado de accesibilidad (`TouchInjectionService`, mando flotante por `dispatchGesture`).

## Estructura

```
app/src/main/aidl/.../shizuku/IGameCastService.aidl   Interfaz del servicio de usuario de Shizuku
app/src/main/java/com/example/gamepadhid/
  MainActivity, ControllerActivity, SettingsActivity, Permissions, Prefs
  shizuku/  ShizukuManager, GameCastClient, GameCastUserService (proceso shell), ShizukuSetupActivity, DiagLog
  shell/    InputInjection, PointerInjector (compartidos con el respaldo)
  virtual/  GameVirtualDisplay, GameLauncher, TouchInjectorShizuku, BinderInputBackend, ScreenInfo
  console/  ConsoleService, ConsoleSession
  overlay/  FloatingPadService, ControlMapperActivity, MapperView, PackagePicker
  mapping/  GameMapping, ControlIds, MappingStore
  hid/      HidManager, HidService, HidSession, GamepadState, GamepadDescriptor, PairingDialog, HidTexts
  cast/     ProjectionService, TvOutputWindow, DisplayScanner, TvPicker, TargetAdapter, CastCapabilities, CastNav
  ui/       JoystickView, Fx, AspectFrameLayout
.github/workflows/build.yml   Compilación en GitHub Actions
SEGURIDAD.md                  Qué hace Shizuku y qué hace (y no hace) esta app
```
