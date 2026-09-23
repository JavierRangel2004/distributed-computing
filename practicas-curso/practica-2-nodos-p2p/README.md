# Práctica 2: Construcción y Conexión de Nodos P2P (Malla ADS)

Malla descentralizada de nodos en Python para la serie de talleres de **Sistemas Autónomos Descentralizados (ADS)**.

> ⚠️ **Aviso de separación de alcance:** Esta carpeta contiene prácticas y talleres de aula del curso y **no forma parte ni altera** la arquitectura ni los requerimientos del proyecto principal de la Calculadora Distribuida de Tres Capas en Java.

---

## 🚀 Inicio Rápido en el Aula (Módem del Profesor)

1. **Cámbiate a la red Wi-Fi del módem del profesor** (`GLSPT 1200` o la subred asignada `192.168.8.x`).
2. **Ejecuta el script lanzador:**
   ```bash
   ./run-node.sh
   ```
   El script detectará automáticamente tu IP local en la interfaz Wi-Fi (ej. `192.168.8.45`) y arrancará el nodo escuchando en el puerto `8070`.
3. **El nodo intentará conectarse automáticamente** a los peers definidos en `conf/connections.json` (por defecto `192.168.8.1:8070` del profesor).

---

## ⌨️ Comandos de la Consola Interactiva (`nodo>`)

| Comando | Descripción | Ejemplo |
| :--- | :--- | :--- |
| `/conectar IP:PUERTO` | Conecta manualmente con otro nodo compañero | `/conectar 192.168.8.15:8070` |
| `/peers` | Lista conexiones salientes y entrantes activas | `/peers` |
| `/enviar <texto>` | Difunde un mensaje de texto por la malla (Gossip) | `/enviar Hola equipo desde el nodo 3` |
| `/ping` | Envía un latido PING manual a los nodos conectados | `/ping` |
| `/ping_auto [on\|off]` | Activa o desactiva el PING periódico de 2 segundos | `/ping_auto off` |
| `/ayuda` | Muestra la ayuda de comandos | `/ayuda` |
| `salir` | Cierra sockets y termina el proceso limpiamente | `salir` |

---

## ⚙️ Estructura de Configuración

- **`conf/config.json`**:
  ```json
  {
      "node_id": "EQUIPO_03",
      "host": "0.0.0.0",
      "port": 8070
  }
  ```
  *(Nota: `0.0.0.0` permite recibir conexiones sin importar qué IP te asigne el módem).*

- **`conf/connections.json`**:
  ```json
  [
      "192.168.8.1:8070"
  ]
  ```

- **`logs/bitacora.log`**: Registra todos los eventos (conexiones entrantes/salientes, mensajes, pings, fallos y desconexiones) con marca temporal ISO.
