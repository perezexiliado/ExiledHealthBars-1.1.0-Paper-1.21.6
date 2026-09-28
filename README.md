# ExiledHealthBars 1.1.0 — Exiliados MC

Actualización del plugin para **Paper 1.21.6 / Java 21**, sin dependencias de terceros en ejecución.

## Cambios

- **Modo predeterminado: `COMBAT`**. La barra aparece únicamente después de que un jugador dañe a un mob, ya sea cuerpo a cuerpo o con un proyectil. Cada nuevo ataque reinicia su duración.
- Diseño sólido enmarcado (`[██████░░░░░░]`) con gradiente **RGB continuo**: `#00FF00` al 100 %, `#FFFF00` al 50 % y `#FF0000` al 0 %.
- En modo `NEARBY`, solo escanea entidades cercanas a jugadores una vez por segundo. En ambos modos limita a 500 barras simultáneas y actualiza vida y color cada 5 ticks.
- Conserva el modo opcional `NEARBY` para servidores que prefieran barras siempre visibles alrededor de los jugadores.
- Restaura los nombres originales al alejarse, morir el mob, descargarse su chunk, recargar o deshabilitar el plugin. Guarda copia de seguridad por entidad en PDC para intentar recuperar nombres después de un cierre inesperado.
- Los mensajes de muerte muestran el nombre real del mob atacante en lugar del texto de su barra de vida, también cuando ataca mediante un proyectil.
- Si otro plugin cambia el nombre mientras se muestra una barra, no vuelve a pisárselo hasta que el mob salga del área rastreada.

## Compilar e instalar

1. Abrí esta carpeta en VS Code con Java 21 y Maven.
2. Ejecutá `mvn clean package`.
3. Apagá Paper. Quitá **el JAR anterior** de ExiledHealthBars de `plugins/`.
4. Subí `target/ExiledHealthBars-1.1.0.jar` a `plugins/` y encendé el servidor.
5. Ejecutá `/exhealth status`. Para recargar opciones: `/exhealth reload` (permiso OP `exhealth.admin`).

Si venís de la 1.0.0, **renombrá `plugins/ExiledHealthBars/config.yml` a `config-antigua.yml` antes del primer arranque**, para generar el nuevo archivo comentado; luego copiá solo las opciones que quieras conservar. Si conservás la configuración antigua, los campos ausentes toman los valores predeterminados del código, incluido el modo `COMBAT`.

## Opciones principales

- `display.mode: COMBAT` (o `NEARBY` si querés verlas siempre sobre los mobs cercanos).
- `combat.duration-seconds: 10` controla cuánto tiempo permanece la barra desde el último ataque.
- `display.radius-blocks: 32` y `display.scan-interval-ticks: 20` se aplican al modo `NEARBY`; `display.max-tracked-mobs: 500` limita las barras simultáneas en ambos modos.
- `bar.length: 12`, `bar.show-numbers: true`, `bar.show-mob-name: false`.
- `entities.include-custom-named-mobs: true`: se guardan los nombres anteriores, pero puede haber conflictos con MythicMobs, Citizens u otros plugins que actualizan nombres. Ponelo en `false` para no tocar nombres personalizados.
- `entities.include-bosses: true`: Wither y Ender Dragon ya tienen otra barra nativa. Si molesta, usar `false`.
- `entities.include-npcs: false`: excluye NPCs con metadata `NPC`.

## Compatibilidad con Bedrock

Se muestra mediante el nombre visible vanilla del mob, sin pack de texturas ni hologramas. Geyser suele transmitir estos nombres, aunque el renderizado y la fidelidad de colores RGB puede variar entre clientes Bedrock. No altera modelos ni ítems de ItemsAdder.

## Límites y pruebas

No hay forma de mostrar una barra encima del mob sin potencial interacción con otros plugins que controlan el mismo nametag usando esta estrategia vanilla. El límite de 500 significa que en un servidor extremadamente poblado algunas entidades pueden quedar temporalmente sin barra: subilo gradualmente si tu rendimiento lo permite. La API oficial de Paper no está disponible en el entorno de preparación: antes de usarlo en producción, compilar contra la dependencia declarada con Maven y probarlo en tu servidor.

Prueba aislada del formato (sin Paper):

```sh
mkdir -p .check
javac -encoding UTF-8 -d .check src/main/java/me/exi/healthbars/HealthBarFormatter.java src/test/java/me/exi/healthbars/HealthBarFormatterSelfTest.java
java -cp .check me.exi.healthbars.HealthBarFormatterSelfTest
```

### JAR opcional de verificación

Si recibiste por separado `ExiledHealthBars-1.1.0-verificacion.jar`, se generó con Java 21 contra **interfaces locales de compatibilidad**, no contra el JAR oficial de Paper, porque aquí no se disponía de repositorios Maven. Compiló correctamente y superó las pruebas aisladas (colores, oveja pasiva, cambio de vida, salida del rango y restauración del nombre); eso **no garantiza** que funcione en un servidor Paper real. La instalación recomendada sigue siendo compilar el ZIP con `mvn clean package` para producir el JAR oficial para tu entorno.
