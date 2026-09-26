import json
import socket
import threading
import time
import uuid
from collections import deque

import content_codes
from logger import registrar

MAX_LINEA = 16384 # bytes máximos de un mensaje sin salto de línea
MAX_MENSAJES_RECORDADOS = 10000


class ACP:
    """
    Autonomous Control Processor: comunica y filtra.
    Se conecta como cliente a los nodos de la malla, recibe CONTENT, decide si
    su Content Code le interesa y solo entonces se lo entrega al ASM. Nunca toma
    decisiones de negocio: eso es del ASM.
    """

    def __init__(self, node_id, nodos, intereses, asm):
        self.node_id = node_id
        self.nodos = nodos
        self.intereses = set(intereses)
        self.asm = asm
        self.activo = False
        self._conexiones = {}          # "ip:puerto" -> socket
        self._vistos = set()           # msg_id ya procesados
        self._orden_vistos = deque()
        self._lock = threading.Lock()  # protege _conexiones y _vistos
        self._envio = threading.Lock() # evita mezclar mensajes al enviar

        for codigo in sorted(self.intereses - content_codes.TODOS):
            registrar("WARN", f"El interés {codigo!r} no está en content_codes.py")

    # ---------- conexión con la malla ----------

    def iniciar(self):
        self.activo = True
        for direccion in self.nodos:
            try:
                ip, puerto = direccion.rsplit(":", 1)
                destino = (ip, int(puerto))
            except ValueError:
                registrar("ERROR", f"Nodo inválido en la configuración: {direccion!r} (usa IP:PUERTO)")
                continue
            hilo = threading.Thread(target=self._mantener_conexion, args=(direccion, destino), daemon=True)
            hilo.start()

    def detener(self):
        self.activo = False
        with self._lock:
            sockets = list(self._conexiones.values())
        for s in sockets:
            try:
                s.close()
            except OSError:
                pass

    def _mantener_conexion(self, direccion, destino):
        espera = 1
        while self.activo:
            try:
                s = socket.create_connection(destino, timeout=5)
            except OSError as e:
                registrar("ERROR", f"No se pudo conectar a {direccion}: {e}. Reintento en {espera}s")
                time.sleep(espera)
                espera = min(espera * 2, 10)
                continue

            s.settimeout(None)
            espera = 1
            with self._lock:
                self._conexiones[direccion] = s
            registrar("CONN", f"Conectado al nodo {direccion}")
            self._enviar_a(s, {
                "type": "HELLO", "node_id": self.node_id,
                "role": "app", "app_type": type(self.asm).__name__,
            })

            self._leer(s, direccion)

            with self._lock:
                self._conexiones.pop(direccion, None)
            try:
                s.close()
            except OSError:
                pass
            if self.activo:
                registrar("DISCONN", f"Conexión perdida con {direccion}")

    def _leer(self, s, direccion):
        buffer = b""
        while self.activo:
            try:
                data = s.recv(4096)
            except OSError:
                break
            if not data:
                break
            buffer += data
            while b"\n" in buffer:
                linea, buffer = buffer.split(b"\n", 1)
                linea = linea.strip()
                if linea:
                    self._recibir(linea.decode("utf-8", errors="replace"), direccion)
            if len(buffer) > MAX_LINEA:
                registrar("DROP", f"más de {MAX_LINEA} bytes sin salto de línea | {direccion}")
                buffer = b""

    # ---------- entrada: malla -> ACP -> ASM ----------

    def _recordar(self, msg_id):
        repetido = msg_id in self._vistos
        if not repetido:
            self._vistos.add(msg_id)
            self._orden_vistos.append(msg_id)
            while len(self._orden_vistos) > MAX_MENSAJES_RECORDADOS:
                self._vistos.discard(self._orden_vistos.popleft())
        return not repetido

    def _recibir(self, texto, direccion):
        try:
            mensaje = json.loads(texto)
        except (ValueError, RecursionError):
            registrar("DROP", f"no es JSON válido | {direccion} | {texto[:60]!r}")
            return
        if not isinstance(mensaje, dict):
            registrar("DROP", f"no es un objeto JSON | {direccion}")
            return
        if mensaje.get("type") != "CONTENT":
            return # PING, HELLO, ACK o TEXT de la Práctica 2: no son de este subsistema

        motivo = self._validar(mensaje)
        if motivo:
            registrar("DROP", f"{motivo} | {direccion}")
            return

        msg_id = mensaje["msg_id"]
        with self._lock:
            nuevo = self._recordar(msg_id)
        if not nuevo:
            registrar("DROP", f"duplicado msg_id={msg_id[:8]}")
            return

        codigo, dato = mensaje["content_code"], mensaje["data"]
        registrar("RX", f"{codigo}={dato!r} de {mensaje['node_id']} id={msg_id[:8]}")

        if codigo not in self.intereses:
            registrar("IGNORE", f"{codigo} no está en mis intereses")
            return

        registrar("USE", f"{codigo}={dato!r} entregado al ASM")
        try:
            nuevos = self.asm.procesar(mensaje)
        except Exception as e:
            registrar("ERROR", f"El ASM falló procesando {codigo}: {e}")
            return
        if isinstance(nuevos, tuple):
            nuevos = [nuevos]
        for nuevo_codigo, nuevo_dato in (nuevos or []):
            self.publicar(nuevo_codigo, nuevo_dato)

    @staticmethod
    def _validar(mensaje):
        for campo in ("node_id", "msg_id", "content_code"):
            valor = mensaje.get(campo)
            if not isinstance(valor, str) or not valor.strip():
                return f"falta {campo} (texto no vacío)"
        if "data" not in mensaje:
            return "falta el campo data"
        return None

    # ---------- salida: ASM -> ACP -> malla ----------

    def publicar(self, codigo, dato):
        """Publica un CONTENT en todos los nodos conectados. Devuelve a cuántos se envió."""
        if codigo not in content_codes.TODOS:
            registrar("DROP", f"content_code desconocido: {codigo!r}")
            return 0
        msg_id = str(uuid.uuid4())
        mensaje = {"type": "CONTENT", "node_id": self.node_id, "msg_id": msg_id,
                   "content_code": codigo, "data": dato}
        with self._lock:
            self._recordar(msg_id) # para descartar si la malla nos lo devuelve
            destinos = list(self._conexiones.values())

        enviados = sum(1 for s in destinos if self._enviar_a(s, mensaje))
        registrar("TX", f"{codigo}={dato!r} -> {enviados} nodo(s) id={msg_id[:8]}")
        return enviados

    def _enviar_a(self, s, mensaje):
        try:
            datos = (json.dumps(mensaje) + "\n").encode("utf-8")
        except (TypeError, ValueError) as e:
            registrar("DROP", f"el dato no es serializable a JSON: {e}")
            return False
        try:
            with self._envio:
                s.sendall(datos)
            return True
        except OSError:
            try:
                s.close() # el hilo lector lo detecta y reconecta
            except OSError:
                pass
            return False
