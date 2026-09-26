# Práctica 3: Subsistema ADS (ASM + ACP)

Implementación formal del paradigma **Autonomous Decentralized Systems (ADS)** utilizando mensajería orientada a contenido sobre el Campo de Datos (*Data Field*).

> ⚠️ **Aviso de separación de alcance:** Esta carpeta contiene prácticas de clase del curso y **no forma parte** del proyecto general de la Calculadora de Tres Capas en Java.

---

## 🏛 Arquitectura del Nodo

```
Malla TCP / Data Field  <===>  ACP (acp.py)  <===>  ASM (asm.py)
```
* **ACP ("Comunica y Filtra"):** Valida paquetes, filtra duplicados, evalúa contra la lista de `INTERESES` (`[USE]` vs `[IGNORE]`), y difunde nuevos contenidos (`[TX]`) y retransmisiones (`[FWD]`).
* **ASM ("Piensa y Actúa"):** Mantiene el estado local (Subsistema: **Aire Acondicionado Inteligente**) y decide de forma autónoma cuándo encender o apagar sin depender de un servidor central.

---

## 🚀 Ejecución Rápida

```bash
./run-node.sh
```

### Comandos de Consola (`ads>`)

| Comando | Acción | Ejemplo |
| :--- | :--- | :--- |
| `/publicar <CODIGO> <VALOR>` | Emite un dato con Content Code al Campo de Datos | `/publicar TEMPERATURA 30.5` |
| `/estado` | Muestra el estado del Aire Acondicionado y umbral | `/estado` |
| `/intereses` | Consulta o modifica los Content Codes de interés | `/intereses +HUMEDAD` |
| `/umbral <VALOR>` | Ajusta el umbral de disparo del termostato | `/umbral 26.5` |
| `/peers` | Consulta conexiones activas en la malla | `/peers` |
| `/catalogo` | Despliega todos los Content Codes reconocidos | `/catalogo` |
| `salir` | Finaliza el nodo y cierra sockets | `salir` |
