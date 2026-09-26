import json
import os
import socket
import sys
import threading
import unittest
import uuid

SRC = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "src"))
sys.path.insert(0, SRC)

import peers
import server


class ProtocolTests(unittest.TestCase):
    def setUp(self):
        peers.cerrar_todas()
        with server._lock_mensajes:
            server.mensajes_vistos.clear()
            server.mis_mensajes.clear()
            server._orden_vistos.clear()
            server._orden_propios.clear()

    def tearDown(self):
        peers.cerrar_todas()

    def test_ack_requiere_msg_id(self):
        mensaje = {"type": "ACK", "node_id": "B", "ack_msg_id": "texto-1"}
        self.assertEqual("ACK sin msg_id", server.validar_mensaje(mensaje))

    def test_ack_se_retransmite_una_sola_vez(self):
        enviados = []
        original = server.enviar_mensaje_a_todos
        server.enviar_mensaje_a_todos = lambda mensaje, excluir=None: enviados.append((mensaje, excluir))
        try:
            mensaje = {
                "type": "ACK",
                "node_id": "B",
                "msg_id": str(uuid.uuid4()),
                "ack_msg_id": str(uuid.uuid4()),
            }
            texto = json.dumps(mensaje)
            server.procesar_mensaje(texto, ("127.0.0.1", 9000))
            server.procesar_mensaje(texto, ("127.0.0.1", 9000))
        finally:
            server.enviar_mensaje_a_todos = original
        self.assertEqual(1, len(enviados))

    def test_lector_procesa_socket_bidireccional(self):
        local, remoto = socket.socketpair()
        recibidos = []
        original = server.procesar_mensaje
        server.procesar_mensaje = lambda texto, addr: recibidos.append(json.loads(texto))
        try:
            direccion = "peer:8070"
            peers.registrar(direccion, local)
            hilo = threading.Thread(
                target=server.leer_conexion,
                args=(local, ("127.0.0.1", 8070), direccion, "PRUEBA"),
            )
            hilo.start()
            remoto.sendall(b'{"type":"PING","node_id":"B","msg_id":"1"}\n')
            remoto.close()
            hilo.join(2)
            self.assertFalse(hilo.is_alive())
            self.assertEqual("PING", recibidos[0]["type"])
            self.assertNotIn(direccion, peers.direcciones())
        finally:
            server.procesar_mensaje = original
            local.close()


if __name__ == "__main__":
    unittest.main()
