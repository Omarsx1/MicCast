#!/usr/bin/env python3
"""
MicCast Test Receiver (Python 3)
Compatible con macOS, Linux y Windows.

Valida de inmediato la Etapa 1 del protocolo MicCast:
1. TCP 51516: Handshake Challenge-Response con HMAC-SHA256 y PIN de 6 dígitos.
2. UDP 51515: Recepción de datagramas de audio con cabecera de 16 bytes.
3. TCP 51517: Recepción de stream de audio por cable USB (adb reverse).
4. Guarda el audio recibido en 'miccast_test_output.wav' (48 kHz, 16-bit Mono PCM)
   y opcionalmente lo reproduce si 'sounddevice' o 'pyaudio' están instalados.

Uso:
    python3 scripts/receiver_test.py
"""

import socket
import struct
import hmac
import hashlib
import os
import random
import threading
import time
import wave
import sys

UDP_AUDIO_PORT = 51515
TCP_CONTROL_PORT = 51516
TCP_USB_PORT = 51517

MAGIC_HEADER = b'MC'
PROTOCOL_VERSION = 0x02

MAGIC_CONTROL = b'MCSC'
STATUS_OK = 0x00
STATUS_AUTH_FAILED = 0x01
STATUS_BUSY = 0x02
STATUS_LOCKED_OUT = 0x03

# Generate a random 6-digit PIN for this session
ACTIVE_PIN = f"{random.randint(100000, 999999)}"
ACTIVE_SESSION_ID = random.randint(1000000, 9999999)

WAV_FILE_PATH = "miccast_test_output.wav"

audio_bytes_queue = bytearray()
is_running = True
last_seq = None
total_packets_received = 0
total_packets_lost = 0

print("=" * 64)
print("🎙️   MICCAST TEST RECEIVER (v2)")
print("=" * 64)
print(f"🔑  CÓDIGO PIN DE EMPAREJAMIENTO: >>> {ACTIVE_PIN} <<<")
print(f"📡  Puerto TCP Control: {TCP_CONTROL_PORT}")
print(f"🎧  Puerto UDP Audio (Wi-Fi): {UDP_AUDIO_PORT}")
print(f"🔌  Puerto TCP Audio (USB): {TCP_USB_PORT}")
print(f"💾  Archivo de grabación: {WAV_FILE_PATH}")
print("=" * 64)
print("Esperando conexión desde la app Android de MicCast...")


def handle_control_connection(conn, addr):
    global ACTIVE_SESSION_ID
    print(f"\n[CONTROL] 📱 Conexión entrante desde {addr[0]}:{addr[1]}")
    try:
        # 1. Enviar Nonce aleatorio de 16 bytes
        nonce = os.urandom(16)
        conn.sendall(nonce)

        # 2. Recibir respuesta del cliente: Magic 'MCSC' (4B) + Version (1B) + HMAC-SHA256 (32B) = 37 bytes
        data = conn.recv(37)
        if len(data) < 37:
            print("[CONTROL] ❌ Respuesta de handshake truncada.")
            conn.close()
            return

        magic = data[0:4]
        version = data[4]
        client_hmac = data[5:37]

        if magic != MAGIC_CONTROL:
            print(f"[CONTROL] ❌ Magic incorrecto: {magic}")
            conn.close()
            return

        # 3. Validar HMAC con el PIN activo
        expected_hmac = hmac.new(ACTIVE_PIN.encode('utf-8'), nonce, hashlib.sha256).digest()

        if hmac.compare_digest(client_hmac, expected_hmac):
            print(f"[CONTROL] ✅ Autenticación EXITOSA con PIN {ACTIVE_PIN}!")
            # Responder STATUS_OK (0x00) + Session ID (uint32)
            response = struct.pack(">BI", STATUS_OK, ACTIVE_SESSION_ID)
            conn.sendall(response)
            print(f"[CONTROL] 🎫 Session ID asignado: {ACTIVE_SESSION_ID}")

            # Keepalive / RTT Echo loop
            while is_running:
                ping = conn.recv(1)
                if not ping:
                    break
                if ping == b'\x7F':
                    conn.sendall(b'\x7E') # Pong reply
        else:
            print("[CONTROL] ❌ PIN INCORRECTO. Enviando STATUS_AUTH_FAILED.")
            conn.sendall(struct.pack(">B", STATUS_AUTH_FAILED))
    except Exception as e:
        print(f"[CONTROL] Error en socket: {e}")
    finally:
        conn.close()
        print("[CONTROL] Conexión de control cerrada.")


def run_control_server():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", TCP_CONTROL_PORT))
        server.listen(2)
        while is_running:
            conn, addr = server.accept()
            t = threading.Thread(target=handle_control_connection, args=(conn, addr), daemon=True)
            t.start()
    except Exception as e:
        print(f"[CONTROL SERVER ERROR]: {e}")
    finally:
        server.close()


def run_udp_audio_receiver():
    global last_seq, total_packets_received, total_packets_lost
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("0.0.0.0", UDP_AUDIO_PORT))
        print(f"[UDP AUDIO] Escuchando en puerto {UDP_AUDIO_PORT}...")

        last_report = time.time()
        packets_in_second = 0

        while is_running:
            packet, addr = sock.recvfrom(2048)
            if len(packet) < 16:
                continue

            magic = packet[0:2]
            if magic != MAGIC_HEADER:
                continue

            version = packet[2]
            flags = packet[3]
            seq, payload_len, sample_counter, session_id = struct.unpack(">HHII", packet[4:16])

            if session_id != ACTIVE_SESSION_ID:
                # Paquete de otra sesión antigua
                continue

            # Detectar pérdidas con aritmética modular uint16
            if last_seq is not None:
                diff = (seq - last_seq) & 0xFFFF
                if diff > 1 and diff < 32768:
                    lost = diff - 1
                    total_packets_lost += lost

            last_seq = seq
            total_packets_received += 1
            packets_in_second += 1

            payload = packet[16:16 + payload_len]
            audio_bytes_queue.extend(payload)

            now = time.time()
            if now - last_report >= 1.0:
                print(f"[STREAM] Recibidos: {packets_in_second} pkt/s | Total: {total_packets_received} | Pérdidas: {total_packets_lost} | Muestras: {sample_counter}")
                packets_in_second = 0
                last_report = now
    except Exception as e:
        print(f"[UDP AUDIO ERROR]: {e}")
    finally:
        sock.close()


def run_usb_tcp_audio_receiver():
    global last_seq, total_packets_received
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        server.bind(("0.0.0.0", TCP_USB_PORT))
        server.listen(1)
        print(f"[USB TCP AUDIO] Escuchando en puerto {TCP_USB_PORT}...")
        while is_running:
            conn, addr = server.accept()
            print(f"[USB TCP AUDIO] Conexión USB establecida desde {addr}")
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

            header_buf = bytearray(16)
            while is_running:
                # Leer cabecera exacta de 16 bytes
                bytes_read = 0
                while bytes_read < 16:
                    chunk = conn.recv(16 - bytes_read)
                    if not chunk:
                        break
                    header_buf[bytes_read:bytes_read + len(chunk)] = chunk
                    bytes_read += len(chunk)

                if bytes_read < 16:
                    break

                magic = bytes(header_buf[0:2])
                if magic != MAGIC_HEADER:
                    continue

                seq, payload_len, sample_counter, session_id = struct.unpack(">HHII", header_buf[4:16])

                # Leer payload de audio
                payload = bytearray(payload_len)
                p_read = 0
                while p_read < payload_len:
                    chunk = conn.recv(payload_len - p_read)
                    if not chunk:
                        break
                    payload[p_read:p_read + len(chunk)] = chunk
                    p_read += len(chunk)

                if p_read == payload_len:
                    audio_bytes_queue.extend(payload)
                    total_packets_received += 1
            conn.close()
    except Exception as e:
        print(f"[USB TCP ERROR]: {e}")
    finally:
        server.close()


def run_wav_writer():
    time.sleep(2)
    with wave.open(WAV_FILE_PATH, 'wb') as wav_file:
        wav_file.setnchannels(1) # Mono
        wav_file.setsampwidth(2) # 16-bit
        wav_file.setframerate(48000) # 48 kHz

        while is_running:
            if len(audio_bytes_queue) > 480:
                chunk = bytes(audio_bytes_queue[:4800])
                del audio_bytes_queue[:4800]
                wav_file.writeframes(chunk)
            else:
                time.sleep(0.05)


if __name__ == "__main__":
    t_ctrl = threading.Thread(target=run_control_server, daemon=True)
    t_udp = threading.Thread(target=run_udp_audio_receiver, daemon=True)
    t_usb = threading.Thread(target=run_usb_tcp_audio_receiver, daemon=True)
    t_wav = threading.Thread(target=run_wav_writer, daemon=True)

    t_ctrl.start()
    t_udp.start()
    t_usb.start()
    t_wav.start()

    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        print("\nCerrando receptor y guardando archivo WAV...")
        is_running = False
        time.sleep(0.5)
        print(f"✅ Archivo guardado correctamente en: {os.path.abspath(WAV_FILE_PATH)}")
