package de.heldendesbildschirms.mcbedrockconnector

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.*

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "MainActivity"
        private const val SOURCE_PORT = 19132
        private const val DESTINATION_IP = "164.68.125.80"
        private const val DESTINATION_PORT = 19132
        private const val REQUEST_INTERNET_PERMISSION = 123
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Überprüfen, ob die Berechtigung zur Laufzeit angefordert werden muss
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.INTERNET
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Berechtigung zur Laufzeit anfordern
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.INTERNET),
                REQUEST_INTERNET_PERMISSION
            )
        }

        // Starte den UDP-Server in einem separaten Thread
        Thread {
            startUDPServer()
            //startUDPServersThreads() //Das kann zwar alles beschleunigen, crasht aber, weill der Socket nicht empfangen und senden gleichzeitig kann und hier wird ein eigener erstellt in jeder Funktion, also so geht das auch nicht.
        }.start()
    }

    private fun startUDPServersThreads() {
        Thread {
            startUDPServerThread()
        }.start()
        Thread {
            startUDPClientThread()
        }.start()
    }

    private val serverSocketThread = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
    //private val serverSocketThread = DatagramSocket(SOURCE_PORT, InetAddress.getByName("0.0.0.0"))
    private val clientSocketThread = DatagramSocket()
    private val packetServerThread = DatagramPacket(ByteArray(65000), 65000, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)

    private fun startUDPServerThread() {
        var maxPaketSize = 65000
        var timeout = 300
        while (true) {
            try {
                serverSocketThread.setSoTimeout(timeout)
                //val packet = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize)
                //if (clientSocketThread.isConnected){
                    serverSocketThread.receive(packetServerThread)
                    //val sendPacket = DatagramPacket(packet.data, packet.length, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)
                    clientSocketThread.send(packetServerThread)
                Log.d(TAG, "Senden die empfangenen Daten an den Zielserver und warte auf eine Antwort")
                //}
            }  catch (e: Exception) {
                //Log.d(TAG, "$e")
            } finally {
            }
        }
    }

    private fun startUDPClientThread() {
        var maxPaketSize = 65000
        var timeout = 300
        while (true) {
            Log.d(TAG, "Senden die empfangenen${packetServerThread.address}") //Nicht die gleiche Adreese.
            try {
                clientSocketThread.setSoTimeout(timeout)
                val receivePacket = DatagramPacket(
                    ByteArray(maxPaketSize),
                    maxPaketSize,
                    packetServerThread.address,
                    packetServerThread.port
                )
                clientSocketThread.receive(receivePacket)
                receivePacket.address = packetServerThread.address
                receivePacket.port = packetServerThread.port
                Log.d(TAG, "Senden die empfangenen")

                serverSocketThread.send(receivePacket)
                Log.d(TAG, "Senden die empfangenen Daten an den Zielserver und warte auf eine Antwort")
            } catch (e: Exception) {
                //Log.d(TAG, "$e")
            } finally {
            }
        }
    }

    private fun startUDPServerThreadVorlage() {
        var maxPaketSize = 65000
        var timeout = 300
        val serverSocket = DatagramSocket(SOURCE_PORT, InetAddress.getByName("0.0.0.0"))
        serverSocket.setSoTimeout(timeout)
        val packet = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize)
        serverSocket.receive(packet)
        serverSocket.send(startUDPClientThreadVorlage(packet))
    }

    private fun startUDPClientThreadVorlage(packet: DatagramPacket):DatagramPacket {
        var maxPaketSize = 65000
        var timeout = 300
        val clientSocket  = DatagramSocket()
        clientSocket.setSoTimeout(timeout)
        val sendPacket = DatagramPacket(packet.data, packet.length, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)
        clientSocket.send(sendPacket)
        val receivePacket = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize, packet.address, packet.port)
        clientSocket.receive(receivePacket)
        receivePacket.address = packet.address
        receivePacket.port = packet.port
        //clientSocket.send(receivePacket)
        return receivePacket
    }

    private fun startUDPServer() {
        try {
            // Erstelle einen DatagrammSocket zum Empfangen von UDP-Paketen auf dem Quellport
            val serverSocket = DatagramSocket(SOURCE_PORT, InetAddress.getByName("0.0.0.0"))
            val clientSocket  = DatagramSocket()
            // Endlosschleife zum kontinuierlichen Empfangen von Paketen
            var maxPaketSize = 65000
            var timeout = 100
            //clientSocket.connect(InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)

            var debugVar = 0
            while (true) {
                try {
                    debugVar = 0
                    serverSocket.setSoTimeout(timeout)
                    clientSocket.setSoTimeout(timeout)
                    //val buffer = ByteArray(maxPaketSize)
                    val packet = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize)
                    serverSocket.receive(packet) //##### Problemstelle ######
                    //Mögliche Uhrsache, die Sockets warten zu lange auf eine Antwort, das sorgt für eine Verzögerung.
                    debugVar = 1
                    Log.d(TAG, "Send UDP packet: ${packet.length}")
                    // Extrahiere die empfangenen Bytes
                    //val receiveClientData = packet.data.copyOf(packet.length)
                    // Verarbeite die empfangenen Daten (hier können Sie weitere Anpassungen vornehmen)
                    //val receivedData = String(packet.data, 0, packet.length)
                    //val receivedDataHex = packet.data.toHexString()
                    //Log.d(TAG, "Received UDP packet: ${receivepacket.toHexString()}")
                    //var test = packet.address
                    //var test2 = packet.port
                    //Log.d(TAG, "Senden die empfangenen Daten an den Zielserver und warte auf eine Antwort $test $test2")

                    // Sende die empfangenen Daten an den Zielserver und warte auf eine Antwort
                    //val response = sendAndReceiveData(receivepacket)

                    // Erstelle einen Socket zum Senden von Daten an den Zielserver

                    // Sende die Daten an den Zielserver
                    val sendPacket = DatagramPacket(packet.data, packet.length, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)
                    //val hexSendData = sendData.joinToString(separator = " ") { byte -> String.format("%02X", byte) }
                    //Log.d(TAG, "Send UDP packet: $hexSendData")
                    //Thread { //<---
                    clientSocket.send(sendPacket)
                    //}.start()
                    // Empfange die Antwort vom Zielserver
                    //val receiveData = ByteArray(maxPaketSize)
                    val receivePacket = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize, packet.address, packet.port)
                    clientSocket.receive(receivePacket)
                    // Extrahiere die empfangenen Bytes
                    //val receivedBytes = receivePacket.data.copyOf(receivePacket.length)
                    //var length = receivePacket.length
                    //Log.d(TAG, "Received length: length")
                    // Verarbeite die empfangene Antwort
                    //val clinetsendPacket = DatagramPacket(receivePacket.data, receivePacket.length, packet.address, packet.port)
                    receivePacket.address = packet.address
                    receivePacket.port = packet.port
                    //clientSocket.send(receivePacket) //Nicht möglich, der neue Socket würde den alten beim empfangen stören.
                    //sendResponseToClient(receivePacket) //Nicht möglich, der neue Socket würde den alten beim empfangen stören.
                    //Thread {
                    //}.start() // <--- receivePacket ist nicht global
                    serverSocket.send(receivePacket)
                    //}.start() // <--- Führt zum Crash.
                } catch (e: Exception) {
                    Log.d(TAG, "Error UDP packet ${debugVar.toString()} ${serverSocket.isClosed} ${serverSocket.isConnected} ${clientSocket.isConnected}")
                    //serverSocket.bind( InetAddress.getByName("0.0.0.0")
                    //clientSocket?.close()
                    //val serverSocket = DatagramSocket(SOURCE_PORT, InetAddress.getByName("0.0.0.0"))
                    //val clientSocket = DatagramSocket()
                } finally {
                }

                // Senden Sie die Antwort an den Client
                //sendResponseToClient(response, packet.address, packet.port)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error receiving UDP packet: ${e.message}")
        }
    }

    private fun sendAndReceiveData(data: ByteArray): ByteArray {
        val clientSocket  = DatagramSocket()
        return try {
            // Erstelle einen Socket zum Senden von Daten an den Zielserver

            // Sende die Daten an den Zielserver
            val sendData = data
            val sendPacket = DatagramPacket(sendData, sendData.size, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)
            //val hexSendData = sendData.joinToString(separator = " ") { byte -> String.format("%02X", byte) }
            //Log.d(TAG, "Send UDP packet: $hexSendData")
            clientSocket.send(sendPacket)

            // Empfange die Antwort vom Zielserver
            val receiveData = ByteArray(1024)
            val receivePacket = DatagramPacket(receiveData, receiveData.size)
            clientSocket.receive(receivePacket)

            // Extrahiere die empfangenen Bytes
            val receivedBytes = receivePacket.data.copyOf(receivePacket.length)

            // Verarbeite die empfangene Antwort
            return receivedBytes
        } catch (e: Exception) {
            //Log.e(TAG, "Error sending/receiving data: ${e.message}")
            return byteArrayOf() // Leeres ByteArray zurückgeben, wenn ein Fehler auftritt
        } finally {
            clientSocket?.close()
        }
    }

    //private fun sendResponseToClient(response: ByteArray, clientAddress: InetAddress, Port: Int) {
    private fun sendResponseToClient(sendPacket: DatagramPacket) {
        Thread {
            val toClientSocket = DatagramSocket()
            try {
                // Erstelle einen Socket zum Senden der Antwort an den Client

                // Sende die Antwort an den Client
                //val sendPacket = DatagramPacket(response, response.size, clientAddress, Port)
                toClientSocket.send(sendPacket)
                //Log.d(TAG, "Sent UDP response to client: $response")
            } catch (e: IOException) {
                Log.e(TAG, "Error sending response to client: ${e.message}")
            } finally {
                toClientSocket?.close()
            }
        }.start()
    }

    private fun ByteArray.toHexString(): String {
        return this.joinToString(separator = " ") { byte -> String.format("%02X", byte) }
    }
}
