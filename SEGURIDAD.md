# Seguridad y privacidad del Modo Consola (Shizuku)

## Qué es Shizuku (y qué no es)

Shizuku es una app de código abierto (RikkaApps) que permite a otras apps usar permisos de **shell**, los mismos que tiene un
ordenador conectado por ADB. El usuario la arranca (por depuración inalámbrica) y **autoriza una a una** las apps que pueden
usarla. **No es root**: no desbloquea el sistema ni modifica particiones, y deja de funcionar al reiniciar el teléfono hasta que
la vuelvas a arrancar. Puedes retirar el permiso a GameCast en cualquier momento desde la app Shizuku.

## Qué hace GameCast con esos permisos

* **Crear una pantalla virtual** («GameCast») y mandar su imagen a la ventana de la TV.
* **Lanzar el juego que tú eliges** en esa pantalla virtual (`am start --display`).
* **Inyectar toques** en la pantalla virtual cuando pulsas el mando flotante, en los puntos que tú configuraste.
* **Leer, una vez tras lanzar el juego, el listado de actividades del sistema** (`dumpsys activity activities`) solo para
  comprobar en qué pantalla quedó el juego. Se compara con el nombre del paquete del juego y no se guarda ni se muestra.

## Qué NO hace

* **No modifica APKs** de terceros ni da instrucciones para hacerlo.
* **No elimina los botones de los juegos**: los botones del propio juego se siguen viendo en la TV. Solo el mando de GameCast
  queda fuera de la TV.
* **No lee el contenido de otras apps**: no captura la pantalla principal, no lee textos ni ventanas, no usa servicios de
  accesibilidad.
* **No tiene permiso de internet** (`INTERNET`): ni la app ni el servicio de usuario pueden conectarse a la red. Puedes
  comprobarlo en `app/src/main/AndroidManifest.xml` o en Ajustes > Apps > GameCast > Permisos.
* No usa cuentas, anuncios, analíticas ni librerías de pago.

## Qué se guarda (solo en el teléfono)

* Los mapas de controles (`mapping_<paquete>.json`) y unos pocos ajustes (nombre Bluetooth, TV elegida).
* Un registro de diagnóstico **en memoria** (no se escribe en disco) que se borra al cerrar la app; puedes copiarlo tú si
  quieres compartirlo para depurar.

## Otros permisos

* **Mostrar sobre otras apps**: dibuja el mando flotante y la imagen en la TV.
* **Notificaciones y servicios en primer plano**: mantienen vivos el mando, la sesión del juego y las demás funciones; se ven
  en la barra de notificaciones y se detienen desde ella.
* **Dispositivos cercanos (Bluetooth)**: solo para el modo Mando HID.

## Riesgos que debes conocer

* Un servicio con permisos de shell es muy potente. Esta app solo usa los tres usos de arriba, pero un APK **modificado** por
  otra persona podría hacer más. Instala solo APKs que compiles tú del código fuente o descargues del repositorio oficial, y
  revoca el permiso en Shizuku si dejas de usar la app.
* Algunos juegos online prohíben las herramientas de inyección o mapeo de entrada en sus términos. Úsala bajo tu
  responsabilidad.

## Distribución

Por la política sobre pantallas virtuales, inyección de entrada y Shizuku, **esta app no se puede publicar en Google Play**.
Se distribuye por GitHub Releases o F-Droid.

## Cómo desactivarlo

Detén el Modo Consola desde GameCast (botón «Detener», ✕ del mando o la notificación), revoca a GameCast en la app Shizuku
y/o detén Shizuku. Desinstalar GameCast elimina sus datos.
