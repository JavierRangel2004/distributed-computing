"""
peers.py - Registro global de conexiones del nodo P2P.
"""

# Diccionario de conexiones salientes iniciadas por este nodo:
# clave: "IP:PUERTO" (str), valor: socket.socket
conexiones_salientes = {}

# Diccionario o conjunto de conexiones entrantes recibidas por el servidor:
# clave: "IP:PUERTO" (str), valor: socket.socket
conexiones_entrantes = {}
