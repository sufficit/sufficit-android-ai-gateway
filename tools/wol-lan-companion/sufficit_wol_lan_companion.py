#!/usr/bin/env python3
"""Inventariador LAN somente-leitura para o Wake-on-LAN Sufficit."""

import concurrent.futures
import ipaddress
import json
import re
import signal
import socket
import subprocess
import threading
import time
from pathlib import Path

PORT = 45991
REQUEST = "SUFFICIT_WOL_DISCOVER_V1 "
PROTOCOL = "sufficit-wol-inventory-v1"
MAC = re.compile(r"^(?:[0-9a-f]{2}:){5}[0-9a-f]{2}$", re.I)
NONCE = re.compile(r"^[A-Za-z0-9_-]{8,80}$")
STOP = threading.Event()


def ip_json(*args):
    output = subprocess.run(
        ["ip", "-j", *args], stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
        text=True, timeout=10, check=True
    ).stdout
    return json.loads(output or "[]")


def interfaces():
    values = []
    for item in ip_json("-4", "address", "show", "up"):
        name = item.get("ifname", "")
        if not name or name == "lo":
            continue
        mac_file = Path("/sys/class/net") / name / "address"
        mac = mac_file.read_text().strip().lower() if mac_file.exists() else ""
        for address in item.get("addr_info", []):
            if address.get("family") != "inet" or address.get("scope") != "global":
                continue
            network = ipaddress.ip_network(f"{address['local']}/{address['prefixlen']}", strict=False)
            if network.is_private:
                values.append({
                    "name": name, "ip": address["local"], "network": network,
                    "broadcast": address.get("broadcast") or str(network.broadcast_address),
                    "mac": mac if MAC.fullmatch(mac) else None,
                    "wireless": (Path("/sys/class/net") / name / "wireless").exists(),
                })
    return values


def for_ip(value, values):
    try:
        address = ipaddress.ip_address(value)
    except ValueError:
        return None
    return next((item for item in values if address in item["network"]), None)


def inventory():
    nets = interfaces()
    devices = {}
    for item in ip_json("-4", "neigh", "show"):
        ip_value, mac = str(item.get("dst", "")), str(item.get("lladdr", "")).lower()
        if not MAC.fullmatch(mac) or "FAILED" in item.get("state", []) or "INCOMPLETE" in item.get("state", []):
            continue
        network = for_ip(ip_value, nets)
        if network is None:
            continue
        devices[mac] = {
            "ip": ip_value, "mac": mac.upper(), "interface": item.get("dev", network["name"]),
            "broadcast": network["broadcast"], "name": None, "self": False,
        }
    host = socket.gethostname() or "sufficit-companion"
    for network in nets:
        if not network["mac"] or network["wireless"]:
            continue
        devices[network["mac"]] = {
            "ip": network["ip"], "mac": network["mac"].upper(), "interface": network["name"],
            "broadcast": network["broadcast"], "name": host, "self": True,
        }
    return sorted(devices.values(), key=lambda item: tuple(map(int, item["ip"].split("."))))[:128]


def refresh():
    nets, own = interfaces(), {item["ip"] for item in interfaces()}
    targets = []
    for item in nets:
        network = item["network"]
        if network.num_addresses > 256:
            network = ipaddress.ip_network(f"{item['ip']}/24", strict=False)
        targets.extend(str(address) for address in network.hosts() if str(address) not in own)
    def ping(address):
        subprocess.run(["ping", "-n", "-c", "1", "-W", "1", address], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=min(64, len(targets) or 1)) as pool:
        list(pool.map(ping, targets[:254]))


def refresh_loop():
    while not STOP.is_set():
        try:
            refresh()
        except Exception as error:
            print(f"wol-companion: refresh parcial: {error}", flush=True)
        STOP.wait(300)


def serve():
    threading.Thread(target=refresh_loop, daemon=True).start()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(("0.0.0.0", PORT))
        server.settimeout(1)
        print(f"wol-companion: UDP {PORT}", flush=True)
        while not STOP.is_set():
            try:
                data, source = server.recvfrom(1024)
            except socket.timeout:
                continue
            text = data.decode("ascii", errors="ignore").strip()
            nonce = text[len(REQUEST):].strip() if text.startswith(REQUEST) else ""
            if not NONCE.fullmatch(nonce) or not for_ip(source[0], interfaces()):
                continue
            body = json.dumps({
                "protocol": PROTOCOL, "nonce": nonce, "companion": socket.gethostname(),
                "generatedAtEpochMs": int(time.time() * 1000), "devices": inventory(),
            }, separators=(",", ":")).encode()
            if len(body) <= 60000:
                server.sendto(body, source)
                print(f"wol-companion: inventario enviado para {source[0]} ({len(json.loads(body)['devices'])} dispositivos)", flush=True)


def main():
    def stop(*_): STOP.set()
    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    serve()


if __name__ == "__main__":
    main()
