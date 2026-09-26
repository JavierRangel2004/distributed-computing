"""
acp.py - Autonomous Control Processor (ACP) para ADS.
"ACP comunica y filtra".

Responsabilidades:
1. Escuchar los paquetes del Campo de Datos (Malla TCP).
2. Filtrar por identificador único (message_id) para evitar bucles.
3. Evaluar el Content Code contra la lista local de INTERESES:
   - Si coincide -> entrega al ASM y registra [USE].
   - Si no coincide -> descarta para el ASM y registra [IGNORE].
4. Retransmitir hacia los demás peers (Gossip / [FWD]) para no romper la propagación en la malla.
5. Empaquetar y publicar nuevos datos generados por el ASM o por consola [TX].
"""

import uuid
import json
from content_codes import TEMPERATURA, ESTADO_AIRE
from logger import registrar_evento

class ACP:
    def __init__(self, node_id, asm_instance, intereses_iniciales=None):
        self.node_id = node_id
        self.asm = asm_instance
        self.intereses = set(intereses_iniciales) if intereses_iniciales else {TEMPERATURA}
        self.mensajes_vistos = set()
        self.fn_enviar_a_todos = None  # Se inyecta desde el módulo cliente

    def set_emisor(self, fn_enviar):
        """Inyecta la función de transporte de red para difusión en la malla."""
        self.fn_enviar_a_todos = fn_enviar

    def agregar_interes(self, content_code):
        """Agrega dinámicamente un Content Code a los intereses locales."""
        self.intereses.add(content_code.upper())
        registrar_evento("SYS", f"Nuevo interés configurado: {content_code.upper()}")

    def remover_interes(self, content_code):
        """Remueve un Content Code de los intereses locales."""
        self.intereses.discard(content_code.upper())
        registrar_evento("SYS", f"Interés removido: {content_code.upper()}")

    def procesar_paquete_red(self, raw_str, addr):
        """
        Punto de entrada para paquetes TCP que entran desde la malla.
        Aplica filtro de unicidad, filtrado por contenido y retransmisión.
        """
        try:
            paquete = json.loads(raw_str)
        except json.JSONDecodeError:
            registrar_evento("DROP", f"Mensaje corrupto / no JSON desde {addr[0]}")
            return

        tipo = paquete.get("type")
        msg_id = paquete.get("message_id") or paquete.get("msg_id")

        # 1. Filtro anti-bucles: si ya vimos este ID, descartamos inmediatamente
        if msg_id:
            if msg_id in self.mensajes_vistos:
                registrar_evento("DROP", f"Descarte por duplicado: {msg_id}")
                return
            self.mensajes_vistos.add(msg_id)

        # 2. Soportar mensajes de control retrocompatibles (HELLO, PING, etc.)
        if tipo == "HELLO":
            origen = paquete.get("node_id", "Anónimo")
            print(f"\n[👋 SALUDO] El nodo '{origen}' ({addr[0]}) se ha conectado.")
            registrar_evento("RX", f"HELLO recibido de {origen} ({addr[0]})")
            return

        if tipo == "PING":
            origen = paquete.get("node_id", "Anónimo")
            registrar_evento("RX", f"PING recibido de {origen}")
            # Retransmitir PING por la malla
            if self.fn_enviar_a_todos:
                self.fn_enviar_a_todos(paquete)
            return

        # 3. Mensaje semántico formal de la Práctica 3 (type: CONTENT)
        if tipo == "CONTENT":
            content_code = paquete.get("content_code")
            data = paquete.get("data")
            origin = paquete.get("origin", "Desconocido")

            registrar_evento("RX", f"Recibido {content_code}={data} de {origin} (id: {msg_id})")

            # Evaluación de Intereses del Subsistema
            if content_code in self.intereses:
                print(f"\n[🎯 ACP USE] Coincide interés '{content_code}' de '{origin}': {data}")
                registrar_evento("USE", f"Entregando a ASM -> {content_code}: {data}")

                # Entrega de datos filtrados al ASM ("ASM piensa y actúa")
                resultado_asm = self.asm.procesar(paquete)

                # Si el ASM generó una reacción que deba publicarse a la malla
                if resultado_asm:
                    nuevo_codigo, nuevo_valor = resultado_asm
                    self.publicar_contenido(nuevo_codigo, nuevo_valor)
            else:
                print(f"\n[🙈 ACP IGNORE] '{content_code}' ignorado localmente (no está en {list(self.intereses)}).")
                registrar_evento("IGNORE", f"Descartado para ASM local: {content_code} de {origin}")

            # Reenvío Gossip: aunque a este nodo no le interese, los demás nodos
            # de la malla podrían necesitarlo (cooperación descentralizada)
            if self.fn_enviar_a_todos:
                registrar_evento("FWD", f"Retransmitiendo {content_code} (id: {msg_id}) a la malla")
                self.fn_enviar_a_todos(paquete)
            return

        # Otros mensajes
        registrar_evento("DROP", f"Tipo de paquete desconocido: {tipo}")

    def publicar_contenido(self, content_code, data):
        """
        Crea un mensaje CONTENT formal, lo registra como visto y lo difunde a la malla.
        """
        msg_id = f"{self.node_id}-{uuid.uuid4().hex[:6]}"
        self.mensajes_vistos.add(msg_id)

        paquete = {
            "type": "CONTENT",
            "message_id": msg_id,
            "origin": self.node_id,
            "content_code": content_code,
            "data": data,
        }

        print(f"\n[🚀 ACP TX] Publicando a la malla: {content_code} = {data} (id: {msg_id})")
        registrar_evento("TX", f"Difundiendo {content_code}={data} (id: {msg_id})")

        if self.fn_enviar_a_todos:
            self.fn_enviar_a_todos(paquete)
        else:
            print("[!] Advertencia: No hay función de envío configurada en el ACP.")

        # Loopback local: si lo publicado coincide con nuestros intereses,
        # también lo entregamos al ASM local para evaluar la reacción
        if content_code in self.intereses:
            registrar_evento("USE", f"Loopback local para ASM -> {content_code}: {data}")
            resultado_asm = self.asm.procesar(paquete)
            if resultado_asm:
                reac_code, reac_val = resultado_asm
                self.publicar_contenido(reac_code, reac_val)
