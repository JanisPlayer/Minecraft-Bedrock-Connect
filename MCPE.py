#from scapy.all import IP, UDP, send
import socket
import random

# Definition der Paketklassen
class MinecraftPacket:
    def __init__(self, packet_id):
        self.packet_id = packet_id

class ConnectedPing(MinecraftPacket):
    def __init__(self):
        super().__init__(0x00)

class ConnectedPong(MinecraftPacket):
    def __init__(self):
        super().__init__(0x01)

class UnconnectedPing(MinecraftPacket):
    def __init__(self):
        super().__init__(0x03)

class UnconnectedPong(MinecraftPacket):
    def __init__(self):
        super().__init__(0x1c)

class OpenConnectionRequest1(MinecraftPacket):
    def __init__(self):
        super().__init__(0x05)

class OpenConnectionReply1(MinecraftPacket):
    def __init__(self):
        super().__init__(0x06)

class OpenConnectionRequest2(MinecraftPacket):
    def __init__(self):
        super().__init__(0x07)

class OpenConnectionReply2(MinecraftPacket):
    def __init__(self):
        super().__init__(0x08)

class ConnectionRequest(MinecraftPacket):
    def __init__(self):
        super().__init__(0x09)
        
class StructureTemplateDataRequest(MinecraftPacket):
    def __init__(self):
        super().__init__(0x84)

# Funktion zum Lesen des Pakets und Erstellung des entsprechenden Objekts
def read_packet(data):
    packet_id = data[0]
    packet_classes = {
        0x00: ConnectedPing,
        0x01: UnconnectedPing,
        0x03: ConnectedPong,
        0x1c: UnconnectedPong,
        0x05: OpenConnectionRequest1,
        0x06: OpenConnectionReply1,
        0x07: OpenConnectionRequest2,
        0x08: OpenConnectionReply2,
        0x09: ConnectionRequest,
        0x84: StructureTemplateDataRequest
    }
    packet_class = packet_classes.get(packet_id)
    if packet_class:
        return packet_class()
    else:
        return None

# Socket initialisieren und an Adresse binden
server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
server.bind(('0.0.0.0', 19132))

# Offline-Nachricht und Server-ID definieren
OFFLINE_MESSAGE_DATA_ID = bytearray([
    0, 0xff, 0xff, 0, 0xfe, 0xfe, 0xfe, 0xfe, 0xfd, 0xfd, 0xfd, 0xfd,
    0x12, 0x34, 0x56, 0x78
])
serverId = "MCPE;HeldendesBildschirms;662;1.20.70;0;10;15465882222205915115;heldendesbildschirms;Survival;1;19132;19132;".encode()
ServerGUID = random.getrandbits(64).to_bytes(8, 'big')

# Endlosschleife für die Paketverarbeitung
while True:
    data, address = server.recvfrom(8192)
    print(f"Empfangene Daten vom {address[0]} Client: ", data)
    # Paket lesen und verarbeiten
    packet = read_packet(data)
    if packet:
        print(f"Received packet with ID: {packet.packet_id}")
    else:
        print("Unknown packet type")
    
    # Antwort auf Ping-Paket senden
    if isinstance(packet, UnconnectedPing):
        response = bytearray()
        response.append(0x1c)
        response.extend(data[1:9])
        response.extend(ServerGUID)
        response.extend(OFFLINE_MESSAGE_DATA_ID)
        response.extend(len(serverId).to_bytes(2, 'big'))
        response.extend(serverId)
        server.sendto(response, address)
        #send(IP(src="164.68.125.80", dst=address[0]) / UDP(sport=19132, dport=address[1]) / response)
    # Antwort auf Open Connection Request 1 senden
    elif isinstance(packet, OpenConnectionRequest1):
        response = bytearray()
        response.append(0x06)
        response.extend(OFFLINE_MESSAGE_DATA_ID)
        response.extend(ServerGUID)
        response.append(0x0)
        response.append(0x0)
        server.sendto(response, address)
    elif isinstance(packet, OpenConnectionRequest2):
        response = bytearray()
        response.append(0x08)  # Packet ID für Open Connection Reply 2
        response.extend(OFFLINE_MESSAGE_DATA_ID)  # Offline-Nachricht-Daten-ID
        response.extend(ServerGUID)  # Server GUID
        client_address = bytearray()  # Client-Adresse
        ip_version = 4  # IP-Version (IPv4)
        ip_address = address[0]  # IP-Adresse des Clients
        port = address[1]  # Portnummer des Clients
        client_address.append(ip_version)  # IP-Version hinzufügen
        client_address.extend(socket.inet_aton(ip_address))  # IP-Adresse im erforderlichen Format hinzufügen
        client_address.extend(port.to_bytes(2, 'big'))  # Portnummer als 2-Byte-Wert hinzufügen
        null_padding_size = 0  # Nullpadding-Größe
        use_encryption = 0x00  # Verschlüsselung deaktiviert (false)
        response.extend(client_address)  # Client-Adresse hinzufügen
        response.extend(null_padding_size.to_bytes(2, 'big'))  # Nullpadding-Größe als 2-Byte-Wert hinzufügen
        response.append(use_encryption)  # Verschlüsselung aktivieren (false)
        server.sendto(response, address)
    elif isinstance(packet, StructureTemplateDataRequest):
        response = bytearray()
        response.append(0x84)
        structure_name = "MyHouse"
        response.extend(structure_name.encode())
        nbt_data = b'\x0a\x00\x00\x01\x02\x03\x00'
        success = True
        response.append(success)
        if success:
            response.extend(nbt_data)
        server.sendto(response, address)


