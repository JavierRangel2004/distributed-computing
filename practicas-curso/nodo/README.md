# 🕸️ Nodo P2P - DataField (ADSOA)

Este proyecto es una implementación educativa de un nodo descentralizado basado en los conceptos de ADSOA (Autonomous Decentralized Service Oriented Architecture) y el paradigma del *Data Field* (Campo de Datos). 

En lugar de depender de un servidor central, los nodos se conectan entre sí para formar una red de tipo malla (mesh), compartiendo mensajes y confirmaciones mediante un protocolo Gossip (retransmisión de rumores).

## 🚀 Requisitos y Configuración

Solo necesitas tener **Python 3** instalado (no requiere librerías externas).

Antes de encender tu nodo, edita el archivo `conf/config.json` para darle una identidad:

```json
{
    "node_id": "EQUIPO_01",
    "host": "0.0.0.0",
    "port": 8070,
    "api_host": "0.0.0.0",
    "api_port": 9070
}
```
*   **`node_id`**: El nombre de tu equipo (sirve para que los demás sepan quién escribe).
*   **`host`**: Déjalo siempre en `0.0.0.0`. Si lo cambias a `127.0.0.1`, tu nodo solo aceptará conexiones desde tu propia computadora y nadie más podrá conectarse a ti.
*   **`port`**: El puerto donde tu servidor escuchará conexiones entrantes.
*   **`api_port`**: Puerto HTTP para consultar la topología del nodo.

### 🌐 Antes de conectarte a otros equipos

*   Necesitas la **IP real de la red local** del otro nodo, no `127.0.0.1`/`localhost` (eso solo funciona si ambos nodos corren en la misma máquina). Para ver tu propia IP en Windows: `ipconfig` y busca "Dirección IPv4".
*   Windows puede pedirte permitir el programa en el **Firewall de Windows Defender** la primera vez que arrancas el servidor (o que alguien se conecta a ti). Acepta el permiso para redes privadas.
*   Si están en el Wi-Fi del salón y nadie logra conectarse a nadie, sospechen de **aislamiento de clientes (AP/client isolation)** en el punto de acceso: algunas redes bloquean el tráfico dispositivo-a-dispositivo aunque estén en la misma red. La alternativa es usar un hotspot/switch dedicado para la práctica.

Para conectarte automáticamente a otros nodos, edita el archivo `conf/connections.json` agregando las IPs y Puertos de tus compañeros en forma de lista:
```json
[
    "192.168.1.100:8070",
    "192.168.1.101:8070"
]
```

El archivo también se actualiza automáticamente. Cuando un nodo descubre a
otro mediante `HELLO` o `PEERS`, guarda su dirección de forma atómica y trata
de incorporarlo a la malla. No se registran los subsistemas como peers.

## API de topología

Cada nodo publica un servicio HTTP sin dependencias externas:

```text
GET http://IP_DEL_NODO:9070/health
GET http://IP_DEL_NODO:9070/topology
```

`/topology` devuelve la identidad local, las conexiones activas y los nodos
conocidos. Cada conexión indica `role` (`node`, `app` o `unknown`), `node_id`,
dirección TCP y, cuando está disponible, el `api_endpoint` remoto. Incluye
`Access-Control-Allow-Origin: *` para que una aplicación web pueda consultarlo.

## 💻 Ejecución

**Opción A (Con Python):**
Abre tu terminal en la raíz de esta carpeta y ejecuta:
```bash
python src/main.py
```

**Opción B (Sin necesidad de Python - Versión Portable):**
Hemos generado un programa ejecutable independiente para el Nodo. 
1. Ve a la carpeta `dist/` que está dentro de este proyecto.
2. Ahí encontrarás `NodoDataField.exe`.
3. ¡Copia ese `.exe` junto a la carpeta `conf/` a cualquier computadora con Windows y funcionará con doble clic!

*Si haces cambios en el código de Python y quieres generar un nuevo `.exe`, simplemente haz doble clic en el archivo `build.bat` que está en la raíz de la carpeta.*

## 🛠️ Comandos de Consola (CLI)

Una vez que el nodo esté en ejecución, verás el prompt `nodo>`. Puedes escribir los siguientes comandos:

*   `/conectar IP:PUERTO` : Intenta establecer un "cable" manual hacia otro nodo. Ejemplo: `/conectar 192.168.1.55:8070`
*   `/peers` : Muestra a quién estás conectado actualmente.
*   `/ping` : Envía un latido (mensaje `PING`) manual que rebotará por toda la red. Además, el nodo ya envía un `PING` automático cada **2 segundos** a todos sus peers mientras tenga al menos una conexión activa.
*   `/enviar <mensaje>` : Envía un mensaje de texto al Data Field. Ejemplo: `/enviar Hola red, aquí equipo 1!` (alias: `/decir`)
*   `/contenido CODIGO VALOR` : Publica un mensaje `CONTENT` por contenido en la malla. Ejemplo: `/contenido TEMPERATURA 29.5`
*   `salir` : Apaga tu nodo y cierra el programa de forma segura.

## 📝 Protocolo de Mensajes (JSON)

Toda la comunicación entre los nodos se realiza enviando **un JSON por línea** (cada mensaje debe terminar con un salto de línea `\n`). Es vital incluir el `msg_id` (UUID) para evitar bucles infinitos en la red.

**1. Saludo (HELLO)** - Enviado automáticamente al conectarse a un peer.
```json
{"type": "HELLO", "node_id": "EQUIPO_01", "role": "node", "listen_port": 8070, "api_port": 9070}
```

**2. Latido (PING)** - Para verificar qué nodos están vivos en la red.
```json
{"type": "PING", "node_id": "EQUIPO_01", "msg_id": "550e8400-e29b-41d4-a716-446655440000"}
```

**3. Texto (TEXT)** - El mensaje principal de información que viaja por el Data Field.
```json
{"type": "TEXT", "node_id": "EQUIPO_01", "msg_id": "550e8400-e29b-41d4-a716-446655440111", "text": "Hola red"}
```

**4. Acuse de Recibo (ACK)** - Respuesta automática generada al recibir un texto nuevo.
```json
{"type": "ACK", "node_id": "EQUIPO_02", "msg_id": "550e8400-e29b-41d4-a716-446655440999", "ack_msg_id": "550e8400-e29b-41d4-a716-446655440111"}
```

El `msg_id` identifica al propio ACK y evita que el acuse circule
indefinidamente cuando la malla contiene ciclos. `ack_msg_id` identifica el
mensaje `TEXT` que se está confirmando.

**5. Contenido (CONTENT)** - Información identificada por lo que significa (`content_code`), no por su destinatario. El nodo solo la retransmite; quien decide si le interesa es el ACP de cada subsistema. Requiere `msg_id`, `content_code` (texto) y `data` (cualquier valor JSON).
```json
{"type": "CONTENT", "node_id": "NODO03", "msg_id": "550e8400-e29b-41d4-a716-446655440222", "content_code": "TEMPERATURA", "data": 29.5}
```

**6. Descubrimiento (PEERS)** - Comparte nodos conocidos para que la malla
pueda crecer sin editar manualmente todos los archivos de conexiones.

```json
{"type": "PEERS", "node_id": "EQUIPO_01", "msg_id": "550e8400-e29b-41d4-a716-446655440333", "peers": ["192.168.1.101:8070"]}
```

Todo mensaje que no cumpla el formato (JSON inválido, `type` desconocido, campos faltantes o de tipo incorrecto) se **descarta** y se anota en la bitácora como `MENSAJE DESCARTADO`.

## 🧠 ¿Qué aprenderás con este nodo?

1.  **Doble Rol (Servidor/Cliente):** Tu programa es capaz de escuchar conexiones entrantes en un hilo en segundo plano, mientras al mismo tiempo tú sales a buscar conexiones como cliente.
2.  **Prevención de Bucles:** Si envías un mensaje, tus compañeros lo retransmitirán. Gracias a los identificadores únicos (`msg_id`), el nodo descarta los mensajes repetidos para evitar tormentas de red.
3.  **Acuses de Recibo (ACK):** Al enviar un texto, el nodo original espera que le reboten las confirmaciones (ACKs) de que la información llegó sana y salva al resto de las terminales.
