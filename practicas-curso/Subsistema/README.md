# Subsistema de ejemplo: sensor de temperatura

Ejemplo mínimo de un subsistema ADS con ASM y ACP que se conecta a un nodo de la malla.

```
Nodo (malla)  →  ACP  →  ASM        entrada: el ACP filtra por Content Code
ASM  →  ACP  →  Nodo (malla)        salida: el ACP publica lo que genera el ASM
```

- **ASM** ([src/asm_sensor.py](src/asm_sensor.py)): simula un sensor. Genera `TEMPERATURA` y, si le llega `ESTADO_AIRE = ON`, la temperatura baja. No sabe nada de redes.
- **ACP** ([src/acp.py](src/acp.py)): se conecta a los nodos, valida el formato, evita duplicados por `msg_id`, entrega al ASM solo los Content Codes de `intereses` y publica lo que el ASM genera. No toma decisiones de negocio.
- **Content Codes** ([src/content_codes.py](src/content_codes.py)): catálogo común. El ACP no publica códigos que no estén ahí.

## Configuración (`conf/config.json`)

```json
{
    "node_id": "SENSOR_TEMP_01",
    "nodos": ["127.0.0.1:8070"],
    "intereses": ["ESTADO_AIRE"],
    "intervalo_segundos": 3
}
```

- `nodos`: nodos a los que se conecta y por los que publica (`IP:PUERTO`).
- `intereses`: Content Codes que el ACP entrega al ASM. El resto se ignora.
- Si el nodo se cae, el ACP reintenta conectar cada 1 a 10 segundos.

## Ejecución

Con el nodo ya encendido, desde esta carpeta:

```
.\Scripts\python.exe src\main.py
```

Opcionalmente, otro archivo de configuración: `.\Scripts\python.exe src\main.py ruta\config.json`.

Para ver al sensor reaccionar, en la consola del nodo escribe `/contenido ESTADO_AIRE ON` y luego `/contenido ESTADO_AIRE OFF`.

## Mensaje

```json
{"type": "CONTENT", "node_id": "SENSOR_TEMP_01", "msg_id": "<uuid>", "content_code": "TEMPERATURA", "data": 26.4}
```

## Bitácora (consola y `logs/bitacora.log`)

| Evento | Significado |
|---|---|
| `CONN` / `DISCONN` | conexión con un nodo establecida / perdida |
| `RX` | CONTENT válido recibido |
| `USE` | el Content Code interesa: se entregó al ASM |
| `IGNORE` | el Content Code no interesa |
| `TX` | contenido publicado (y a cuántos nodos) |
| `DROP` | descartado: formato inválido, duplicado o Content Code desconocido |
| `ASM` | decisión del ASM |

El ACP se identifica con `role: app` e ignora en silencio los mensajes de red
(`HELLO`, `PING`, `TEXT`, `ACK` y `PEERS`). No retransmite: de la difusión se
encarga el nodo.
