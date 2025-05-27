# English

# APP:  
![Preview.webp](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/raw/main/Preview.webp)  
https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/tree/master  

# Last Build:  
[Minecraft-Bedrock-Connect.apk](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/raw/main/Minecraft-Bedrock-Connect.apk)  
PlayStore: [Download](https://play.google.com/store/apps/details?id=de.heldendesbildschirms.mcbedrockconnector) [Why The App Was Renamed](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/blob/main/Why_The_App_Was_Renamed.md) [PlayStore Availability](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/blob/main/PlayStore%20Availability.md)  

This project is in no way connected to GKM Interactive or Mojang/Microsoft. It is a purely private, non-commercial open-source project.

# Minecraft-Bedrock-Connect  
An Android app that enables forwarding a server to LAN using packet forwarding.  
This can be useful for consoles like Xbox, PlayStation to connect to external servers.  

# Standard Server  
Permission has been granted by [BedrockConnect](https://github.com/Pugmatt/BedrockConnect/) to use their servers.  
Thanks to this permission, the app requires less maintenance as it is less complex.  

# Useful Information:  
The basic idea of packet forwarding can be tested on Android using UserLAnd:  
```screen -dmS socat socat UDP4-LISTEN:19132,fork,su=nobody UDP4:[164.68.125.80]:19132```  

Protocol:  
- https://wiki.vg/Raknet_Protocol  
- https://wiki.bedrock.dev/  

Proxies:  
- https://github.com/Pugmatt/BedrockConnect/  
- https://github.com/CloudburstMC/Protocol  
- https://github.com/haveachin/infrared  
- https://github.com/cubeworx/cbwxproxy (Docker)  
- https://github.com/cubeworx/cbwxannounce/ (Source)  
- https://github.com/illiteratealliterator/manymine/ (Original)  

Relevant Android networking documentation:  
- https://developer.android.com/reference/java/net/DatagramSocket  
- https://developer.android.com/reference/java/nio/channels/DatagramChannel (Better solution than DatagramSocket)  

Important configurations:  
- `DatagramChannel.configureBlocking(false)`  
- `DatagramChannel.socket().setReceiveBufferSize(MAX_PACKET_SIZE)`  
- `DatagramChannel.socket().setSoTimeout(100)`  
- `DatagramChannel.register(Selector)`  

Example implementation:  
[https://github.com/elixsr/FwdPortForwardingApp/](https://github.com/elixsr/FwdPortForwardingApp/blob/master/app/src/main/java/com/elixsr/portforwarder/forwarding/UdpForwarder.java)  

# How It Works:  
Each Bedrock client sends an Unconnected Ping via the broadcast port using the RakNet protocol.  
This differs from the Java Edition of Minecraft, as no response from the server is required.  
A server bound to `0.0.0.0` can receive these packets.  
The server then redirects the received packets to the target server and relays the responses back to the client.  

# Deutsch

# APP:  
https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/tree/master

# Last Build:
[Minecraft-Bedrock-Connect.apk](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/raw/main/Minecraft-Bedrock-Connect.apk)  
PlayStore: [Download](https://play.google.com/store/apps/details?id=de.heldendesbildschirms.mcbedrockconnector) [Warum die App umbenannt wurde](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/blob/main/Why_The_App_Was_Renamed.md) [PlayStore Verfügbarkeit](https://github.com/JanisPlayer/Minecraft-Bedrock-Connect/blob/main/PlayStore%20Availability.md)  

Dieses Projekt steht in keiner Verbindung zu GKM Interactive oder Mojang/Microsoft. Es handelt sich um ein rein privates, nicht-kommerzielles Open-Source-Projekt.

# Minecraft-Bedrock-Connect
Eine Android App die es ermöglicht einen Server ins LAN weiterzuleiten mit Paket weiterleitung.
Das kann nützlich für Konsolen wie Xbox, Playstation, sein um eine Verbindung zu externen Servern aufzubauen.

# Standard Server  
Die Erlaubnis von [BedrockConnect](https://github.com/Pugmatt/BedrockConnect/) wurde erteilt, die Server mit zu nutzen.  
Dank dieser Erlaubnis ist der Wartungsaufwand der App nicht so hoch, da sie weniger komplex sein muss.

# Nützliche Informationen:  
Grund Idee der Paket weiterleitung, kann unter Android mit Userland getestet werden:  
```screen -dmS socat socat UDP4-LISTEN:19132,fork,su=nobody UDP4:[164.68.125.80]:19132```  
Protocol:  
https://wiki.vg/Raknet_Protocol 
https://wiki.bedrock.dev/  
Proxys:  
https://github.com/Pugmatt/BedrockConnect/  
https://github.com/CloudburstMC/Protocol  
https://github.com/haveachin/infrared  
https://github.com/cubeworx/cbwxproxy (Docker)  
https://github.com/cubeworx/cbwxannounce/ (Source)  
https://github.com/illiteratealliterator/manymine/ (Uhrsprung)  
https://developer.android.com/reference/java/net/DatagramSocket
https://developer.android.com/reference/java/nio/channels/DatagramChannel (Bessere Lösung als DatagramSocket)
Hier ist der DatagramChannel.configureBlocking(false), DatagramChannel.socket().setReceiveBufferSize(MAX_PACKET_SIZE) DatagramChannel.socket().setSoTimeout(100) DatagramChannel.register(Selector) interessant.
[https://github.com/elixsr/FwdPortForwardingApp/](https://github.com/elixsr/FwdPortForwardingApp/blob/master/app/src/main/java/com/elixsr/portforwarder/forwarding/UdpForwarder.java)

# Informationen zu Funktionsweise:
Jeder Bedrock Client sendet über den Broadcast Port einen Unconnected Ping über das Raknet_Protocol, das ist also anderes als wie bei der MC Java Edition und braucht daher keine Nachricht vom Server.
Dieser kann von einem Server der auf 0.0.0.0 gebunden ist empfangen werden.
Der Server adressiert die Empfangenen Pakete um zum Ziel-Server und sendet die vom Ziel-Server empfangen Pakete zurück zum Client.
