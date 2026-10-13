# Parcial 2 ADS — Plan de implementación

**Goal:** entregar calculadora Java ADS con tres programas, malla escalable, cuatro GUI y dieciséis servidores en la demo, README/docs, reporte LaTeX/PDF y notas de setup/video.
**Architecture:** nodos iguales TCP con broadcast de JSON UTF-8 por línea. Descubrimiento distribuido de servicios mediante anuncios con lease; la GUI fija un único servidor en cada solicitud y todos reciben el broadcast. Sin coordinador ni reasignación de una solicitud en vuelo.
**Tech Stack:** Java 21+, Maven existente, Gson, Swing/FlatLaf, JUnit existente, Tectonic.

## Autoridad y gate

- Implementación por hojas separadas y revisión independiente. Los registros privados de coordinación y del entorno quedan en el vault de la materia, no en el repositorio público.
- Checkout de la materia: `distributed-computing`, rama `main`, base `6483057706289a8c7b452cdada195d535ccca685`.
- Push a `origin` autorizado el 13-oct-2026, después de revisar secretos y datos personales; sin coautores ni reescritura de historia. No se publican historiales ni configuración local de conexiones. Sin merge a otras ramas ni acceso a Blackboard.
- Máximo cuatro internos vivos y una capa de delegación. Cada hoja devuelve archivos y evidencia al integrador.
- Locales ajenos: `practicas-curso/nodo/conf/connections.json` y cuatro historiales `data/clients/Client-{1,2}_history.json`, `data/servers/Server-{1,2}_history.json`: preservar, no stage/commit. Ninguna hoja toca prácticas ni esos datos.
- El primer parcial se conserva como entregable histórico independiente. ADS pasa a ser la ruta principal de README/JAR; no usa middleware del parcial 1. No migrar sus archivos de datos ni reescribir sus notas históricas.
- Fecha no confirmada: viernes 23 de octubre de 2026 figura en calendario; PDF no tiene vencimiento y nota automática Blackboard de 13-oct no muestra fecha de esta entrega. Sin lectura web/write a Blackboard.

## Contrato de protocolo e interfaces

Nuevo paquete `mx.edu.up.computing.ads`, sin modificar símbolos públicos históricos.

Puntos de entrada:
- `NodeMain --id N1 --port 6101 --peers N2@127.0.0.1:6102,N3@127.0.0.1:6103,N4@127.0.0.1:6104` (lista variable, no cuatro fijados en código).
- `ServerMain --id N1-SUM-1 --service SUM --host 127.0.0.1 --port 6101` (SUM/SUB/MUL/DIV).
- `ClientMain --id GUI1 --host 127.0.0.1 --port 6101` (GUI por defecto).
- `DemoMain` arranca 4 nodos/4 GUI/16 servidores; `SmokeMain` valida sockets reales sin ventanas y ofrece prueba gráfica con Robot si es viable. Sus cantidades demo no limitan los programas.

Sobre JSON: `type`, `messageId` (UUID completo), `requestId` (solo respuesta, igual al messageId de solicitud), `contentCode`, `originId`, `clientId`, `targetServerId`, `operandA`, `operandB`, `result`, `status`, `error`, `createdAt`, `expiresAt`; campos de registro/advertisement separados cuando proceda. Cada respuesta crea UUID propio. Handshake `HELLO` distingue NODE/GUI/SERVER, identidad y servicio. Identidades activas de célula deben ser únicas: anuncios incluyen identidad de instancia para impedir que dos servidores con igual etiqueta respondan.

- Códigos exactos: SUM_REQ/SUM_RES, SUB_REQ/SUB_RES, MUL_REQ/MUL_RES, DIV_REQ/DIV_RES.
- La GUI recibe anuncios periódicos de servicios de toda la malla, con expiración, ordena candidatos y fija uno al emitir. `targetServerId` debe identificar una instancia única, no solo una etiqueta reutilizable. La elección pertenece a cada emisor, no a una autoridad global. Los otros servidores ignoran por servicio/target pero observan broadcast.
- Anuncios periódicos cada 2 s y lease 6 s (configurables si necesario). Nodos se conectan por peers configurables, sin seed indispensable; enlaces TCP canónicos por orden de ID para evitar duplicación, reconexión y anuncios para altas tardías.
- Vida de mensaje 30 s; expiración original inmutable en cada salto. Cache atómica de IDs retenida 60 s desde primera recepción, limpieza periódica. Validar tiempo antes de procesar: un ID expulsado no revive porque su sobre ya expiró. El servidor aplica deduplicación por requestId además de target; GUI solo acepta primera respuesta válida correlacionada con solicitud pendiente y servidor/código esperado.
- Sin failover automático de una solicitud en vuelo: timeout visible 10 s. Nueva operación tiene nuevo UUID y selección; no se promete exactly-once durable bajo reinicio/partición. Las células reintentan el mismo endpoint; cambiar de nodo requiere intervención del operador.
- Logs por rol: conexión, alta/baja, broadcast, DROP_DUPLICATE, DROP_EXPIRED, IGNORE_SERVICE/TARGET, COMPUTE, RESPONSE, correlación y timeout. Historial ADS separado `data/ads/`, sin tocar históricos del parcial 1.
- División por cero devuelve error correlacionado; valores no finitos no se serializan como números JSON inválidos. GUI nunca calcula.

## Hojas asignadas

### A — Runtime y pruebas de comportamiento

Escritor sobre `src/main/java/mx/edu/up/computing/ads/**`, `src/test/java/mx/edu/up/computing/ads/**` y `pom.xml`.

- [x] Protocolo, transportes, nodos, servidor, cliente Swing, persistencia, CLI, DemoMain y SmokeMain implementados.
- [x] Maven/Gson/FlatLaf existentes reutilizados; sin middleware ni framework nuevo.
- [x] Pruebas permanentes de comportamiento y smoke real: aritmética, correlación, unicidad, replay/expiración, malla y altas; evidencia detallada en `ADS.md` §3.5.1.
- [x] Manifest ADS activo; parcial 1 invocable como histórico independiente.
- [x] Integración final por el padre: `mvn package`, 19 pruebas sin fallos.

### B — README/docs y lanzadores

Escritor sobre `README.md`, `docs/ADS.md`, `run-ads-demo.sh` y `run-ads-demo.ps1`.

- [x] README ADS principal con compilación, tres programas, demo GUI, smoke y navegación histórica.
- [x] Protocolo, selección, TTL/cache, discovery y garantías documentados.
- [x] Lanzadores DemoMain sin reset/kill global. Bash arrancó cuatro ventanas reales y cerró por SIGTERM; PowerShell es receta preparada, no corrida.

### C — Reporte LaTeX

Escritor sobre `05 - Proyecto Parcial 2/export/Reporte-Parcial2-ComputoDistribuido.tex` en el vault de la materia; el PDF se exporta junto a la fuente.

- [x] Reporte con portada, índice, marco, requisitos, arquitectura, diagramas, código, protocolo, persistencia, matriz ejecutada, conclusiones y referencias.
- [x] Patrón del parcial 1 sin copiar arquitectura ni resultados; fecha ADS no confirmada.
- [x] Evidencia real separa smoke de una JVM y CLI de 24 JVM independientes; sin stubs.
- [x] Tectonic produjo PDF de 15 páginas revisado visualmente, sin desbordamientos horizontales.

### D — Notas vault y MOC

Escritor sobre dos notas de `05 - Proyecto Parcial 2/` en el vault de la materia: `Proyecto Parcial 2 — ADS (Guía de Setup, Instalación y Pruebas).md` y `Proyecto Parcial 2 — Video Demo (Guion + Setup Local).md`; además, el MOC de Cómputo Distribuido.

- [x] Guía macOS/Windows y guion de video listos, sin afirmar Windows ejecutado ni video grabado.
- [x] MOC actualizado con entregables ADS y las tres notas de Criptomonedas; contenido ajeno conservado.
- [x] Drive local comprobado y lectura UTF-8 sin BOM; sin afirmar sincronización remota.

## Integración y verificación del padre

- [x] Implementación y revisiones independientes aceptadas; recibos privados en la nota de ejecución del vault.
- [x] Runtime y documentación revisados por lectores distintos de los escritores; hallazgos corregidos.
- [x] Build final: 19 pruebas, cero fallos, errores u omisiones.
- [x] Smoke real: 6 enlaces, 20 células, 16 operaciones iniciales con 16 cómputos, replay, caducidad, altas y fallos.
- [x] Cuatro GUI ejercitadas por clics OS y capturadas; 24 JVM públicas verificadas aparte.
- [x] PDF de 15 páginas compilado y revisado; fuente y PDF en el vault, no duplicados en el repo.
- Publicación autorizada: stage únicamente de productos propios, commits lógicos sin coautores ni historial personal, correo noreply y push normal a origin. El historial remoto y el recibo privado son la evidencia de publicación; no incluir los cinco archivos locales ni Test-*_history.json.

## Alternativas consideradas

- Competición por temporizador y cancelación por primer resultado: puede producir múltiples respuestas en carreras/particiones; descartada.
- Elección independiente sobre membresía local: vistas divergentes pueden elegir dos ganadores; descartada.
- Target inmutable elegido por el emisor desde descubrimiento distribuido: seguro para respuesta única con menor complejidad; sacrifica failover transparente de solicitudes en vuelo y lo declara.
