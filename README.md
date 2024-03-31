# Minecraft-Bedrock-Connect
Eine Android App die es ermöglicht einen Server ins LAN weiterzuleiten mit Paket weiterleitung.
Das kann nützlich für Konsolen wie Xbox, Playstation, Switch, sein um eine Verbindung zu externen Servern aufzubauen.

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

# Informationen zu Funktionsweise:
Jeder Bedrock Client sendet über den Broadcast Port einen Unconnected Ping über das Raknet_Protocol, das ist also anderes als wie bei der MC Java Edition und braucht daher keine Nachricht vom Server.
Dieser kann von einem Server der auf 0.0.0.0 gebunden ist empfangen werden.
Der Server adressiert die Empfangenen Pakete um zum Ziel-Server und sendet die vom Ziel-Server empfangen Pakete zurück zum Client.
