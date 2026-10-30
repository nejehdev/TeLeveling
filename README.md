# TeLeveling

Plugin de niveles configurables para Paper. Permite mantener varios sistemas de progreso por jugador, cada uno definido por un archivo YAML.

## Requisitos

- Java 21 o superior, según lo que requiera la versión del servidor. El plugin detecta e informa la JVM activa una vez cargado; no puede cambiar la JVM con la que se inició Minecraft. Un servidor debe arrancar con Java 21 o superior para cargar este bytecode.
- Paper 1.21 o posterior. El artefacto se compila contra Paper API 1.21.4 y usa bytecode Java 21; Minecraft 26.2 requiere que el servidor se ejecute con Java 25.
- PlaceholderAPI es opcional; sin él siguen funcionando los placeholders propios de TeLeveling.
- En modo semipremium, TeLeveling usa el UUID entregado por Paper. En redes con proxy, configura correctamente el forwarding para que el UUID sea estable entre servidores.

## Archivos de configuración

Al iniciar, el plugin crea `plugins/TeLeveling/config.yml`, `messages.yml` y `levels/`. También añade claves que falten sin reemplazar los valores que ya configuraste. En una carpeta `levels` nueva genera `example.yml`; puedes modificarlo, añadir más `.yml` o eliminarlo. `/televeling reload` vuelve a cargar la configuración y todos los niveles: los comandos de niveles eliminados se desregistran y los nuevos o modificados se registran con sus aliases actualizados. Los datos históricos de los niveles eliminados permanecen en la base de datos, pero el servidor deja de consultarlos.

`xp-max` y `level-max` en `config.yml` limitan los números aceptados por comandos. Sus techos internos son `1,000,000` XP y `10,000` niveles. Si un valor configurado supera su techo, no es numérico o es inválido, se advierte en consola y se guarda el máximo interno en la configuración.

Cada archivo de nivel utiliza su nombre sin extensión como identificador, por ejemplo `levels/bedwars.yml` crea el nivel `bedwars`.

```yaml
name: "BedWars"
command-alias:
  - "bedwarslevel"
  - "bwlevel"
starting-level: 0
starting-experience: 0
symbols:
  1: "✦"
  10: "✪"
format: "%level%%symbol%"
colors:
  1: "&7"
  10: "&b"
  100: "&a,&b,&c"
max-level: -1
permission: ""
column-name: "bedwars_levels"
syncronized: true
experience-require:
  general-formula: "100 * ({level} + 1)"
  level:
    0: 50
```

- `symbols` y `colors` seleccionan la clave más alta que no supere el nivel actual. Varias entradas separadas por coma en `colors` alternan el color a través del texto.
- `format` define exactamente cómo se reemplazan `%level%` y `%symbol%` en el placeholder. Por ejemplo, `format: "%level%%symbol%"` produce `1◆`; las variantes `noicon` y `nocolor` omiten solo el ícono o el color, respectivamente.
- `experience-require.level` tiene prioridad para el nivel indicado; si no existe, se evalúa `general-formula`, donde `{level}` es el nivel actual. Se admiten números, paréntesis y `+`, `-`, `*`, `/`, `%`; no se ejecuta código de la fórmula.
- `max-level: -1` no limita la progresión. En el máximo configurado, la XP deja de aumentar.
- `syncronized: true` guarda los datos en el almacenamiento configurado. `column-name` es una clave estable por nivel compartida por los servidores de la red, no un nombre interpolado en SQL.
- Los jugadores y sus progresos se identifican por UUID; los alias son comandos dinámicos registrados al cargar el plugin.

La configuración `storage.type` acepta `SQLITE` o `MYSQL`. SQLite guarda el archivo en la carpeta del plugin. MySQL permite indicar host, puerto, base de datos, usuario, contraseña, SSL, validación de certificado y recuperación de clave pública. El plugin crea la tabla `televeling_player_levels`; comparte la base MySQL y el mismo `column-name` en cada servidor para sincronizar un nivel. La consola informa cuando la conexión MySQL y la carga de datos de cada jugador terminan correctamente; nunca registra la contraseña.

Por ahora, la experiencia se administra con los comandos de operador; no hay recompensas automáticas por eventos del juego. Los comandos actualizan de inmediato la caché, la barra y los mensajes del jugador; la escritura MySQL se realiza en segundo plano y los errores de persistencia se informan explícitamente. MySQL usa una cola de escritura ordenada y lecturas concurrentes separadas para que los inicios de sesión y la sincronización periódica no bloqueen las mutaciones. `save-time` (segundos, 60 por defecto) determina cada cuánto se vuelve a consultar el almacenamiento para actualizar la caché local de jugadores conectados. Cada actualización consulta en una sola operación todos los niveles de cada jugador conectado. Un intervalo mayor reduce consultas y uso de CPU/DB a cambio de tardar más en reflejar cambios hechos desde otro servidor. Los niveles con `syncronized: false` permanecen solo en memoria durante esa sesión.

Para reducir el consumo, SQLite usa como máximo una conexión; MySQL conserva una conexión caliente y permite hasta cuatro conexiones concurrentes. El reinicio global actualiza el almacenamiento con una sola operación, sin escrituras adicionales por cada jugador conectado. TeLeveling no crea índices secundarios que no necesita para la carga de progreso. Las librerías JDBC se descargan y comparten mediante Paper en lugar de incluir copias en el JAR del plugin; Paper las almacena en su caché de librerías. Esto hace mucho más pequeño el JAR, pero no elimina el espacio que ocupa el motor de base de datos que uses en la caché de Paper.

`level-bar.enabled` muestra el avance del nivel seleccionado por `level-bar.name` en la barra de experiencia de Minecraft: el contador refleja el nivel configurado del jugador y la barra representa su XP actual respecto al requisito de ese nivel. Cuando sube de nivel, el contador aumenta y la barra comienza con la XP restante de ese nuevo nivel. TeLeveling actualiza solo lo que ve el jugador; no modifica su nivel ni su XP vanilla almacenados.

`messages.yml` admite mensajes como texto o listas de líneas. `{line}` repite `line-icon` según el ancho configurado y `{center}` aplica centrado aproximado con `messages.center-width`. Con PlaceholderAPI instalado, también se expanden placeholders externos dentro de los mensajes.

## Comandos

- `/televeling help`
- `/televeling reload`
- `/televeling reset <nivel-id>` reinicia a nivel 0 y 0 XP todos los registros guardados de ese tipo.
- `/televeling` acepta aliases definidos en la lista `aliases` de `config.yml` (por defecto, `tl`).
- Cada archivo de nivel registra un comando principal y sus aliases. Sin argumentos muestra el progreso; `<cmd> help` enseña la ayuda de ese comando.
- `/<cmd> givexp <jugador-conectado> <cantidad>`, `givelevel <jugador-conectado> <cantidad>`, `setxp <jugador-conectado> <cantidad>`, `setlevel <jugador-conectado> <nivel>`, `takexp <jugador-conectado> <cantidad>`, `takelevel <jugador-conectado> <cantidad>` y `reset <jugador-conectado>` administran el progreso.
- `givelevel` y `takelevel` cambian niveles sin alterar la XP actual; `setlevel` fija el nivel conservando la XP actual. `setxp` reemplaza la XP del nivel y aplica los ascensos que correspondan. Al quitar XP se descienden niveles cuando hace falta y la XP restante se descuenta desde la barra del nivel anterior.
- Para limitar cálculos y evitar valores desbordados, cada argumento numérico de comando debe ser positivo y no superar el límite configurado. No se aceptan texto, `NaN`, infinito, notación hexadecimal ni notación científica. Una operación de XP también se rechaza si cruzaría más de `10,000` niveles, incluso si el YAML define requisitos de XP muy pequeños. Para reiniciar a cero se usa `reset`.
- Los subcomandos administrativos aceptan `-s` para silenciar el mensaje al jugador y `-f` para no enviar confirmación a quien ejecutó el comando. Se pueden combinar.

Los comandos administrativos requieren `televeling.admin` (por defecto, operadores). Los jugadores necesitan el permiso configurado en el nivel solo para consultar su progreso.

## Placeholders propios

`[nivel]` es el identificador del archivo YAML, sin `.yml`.

- `%televeling_level_[nivel]%` devuelve el formato del nivel, incluyendo color, número e ícono configurados.
- `%televeling_level_nocolor_[nivel]%`
- `%televeling_level_noicon_[nivel]%`
- `%televeling_level_raw_[nivel]%`
- Las mismas variantes sustituyendo `level` por `nextlevel`: `%televeling_nextlevel_[nivel]%`, `%televeling_nextlevel_nocolor_[nivel]%`, `%televeling_nextlevel_noicon_[nivel]%` y `%televeling_nextlevel_raw_[nivel]%`. La variante completa también devuelve el color, número e ícono del nivel siguiente. Se mantiene compatibilidad con el formato anterior `%televeling_nextlevel_level_[nivel]%`.
- `%televeling_xp_[nivel]%`
- `%televeling_xp_required_[nivel]%`
- También se aceptan `nextlevel_xp_required` para consultar la XP requerida en el nivel siguiente.

## Compilar

```powershell
.\gradlew.bat build
```

El JAR de distribución se genera en `build/libs/TeLeveling-0.1.8.jar`.

## Publicar una release en GitHub

Al subir cualquier etiqueta Git, por ejemplo `0.0.1` o `v0.0.1`, el workflow `.github/workflows/release.yml` compila y prueba el proyecto con Java 21 y publica una GitHub Release cuyo título coincide con la etiqueta. El JAR adjunto se llama `TeLeveling-<etiqueta>.jar`. El workflow necesita el permiso `contents: write`, configurado en el propio archivo.
