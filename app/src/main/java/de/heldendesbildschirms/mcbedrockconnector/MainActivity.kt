package de.heldendesbildschirms.mcbedrockconnector

import android.Manifest
import android.content.pm.PackageManager
import android.os.AsyncTask
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.Callable


class MainActivity : AppCompatActivity() {

    private lateinit var channel: DatagramChannel

    private companion object {
        private const val TAG = "MainActivity"
        private const val SOURCE_PORT = 19132
        private const val DESTINATION_IP = "164.68.125.80"
        private const val DESTINATION_PORT = 19132
        private const val REQUEST_INTERNET_PERMISSION = 123
        private const val MAX_PACKET_SIZE = 65507
        private const val  MAX_DATAGRAM_SIZE = 65507
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
        //val fromAddress = InetSocketAddress("0.0.0.0", 19132)
        //val toAddress = InetSocketAddress("164.68.125.80", 19132)
        //val forwarder = UdpForwarder(fromAddress, toAddress)

        //Thread(PacketForwarder(DESTINATION_PORT , DESTINATION_IP, DESTINATION_PORT)).start()

        //val fromAddress = InetSocketAddress("0.0.0.0", SOURCE_PORT)
        //val toAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT)
        //val ruleName = "ForwardingRule1" // Beispiel: Name der Weiterleitungsregel

        //UdpForwarderTask(fromAddress, toAddress, ruleName)
        Thread {
            startnewUDPServer()
            //startUDPServer()
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
        var maxPaketSize = 65507
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
                    serverSocket.setReceiveBufferSize(maxPaketSize)
                    serverSocket.setSendBufferSize(maxPaketSize)
                    clientSocket.setSoTimeout(timeout)
                    clientSocket.setReceiveBufferSize(maxPaketSize)
                    clientSocket.setSendBufferSize(maxPaketSize)
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

    private fun startnewUDPServer() {
        try {
            // Erstelle einen DatagramChannel zum Empfangen von UDP-Paketen auf dem Quellport
            val channel = DatagramChannel.open()
            channel.socket().bind(InetSocketAddress(SOURCE_PORT))
            channel.socket().setReceiveBufferSize(MAX_PACKET_SIZE)
            channel.socket().setSoTimeout(1000)
            channel.configureBlocking(false)

            val forwardChannel = DatagramChannel.open()
            forwardChannel.socket().setReceiveBufferSize(MAX_PACKET_SIZE)
            forwardChannel.socket().setSoTimeout(100) //There are problems maintaining the connection. Maybe this needs to be written differently with disconnect or close. This helps somewhat to reduce the lag, but is not a real solution.
            forwardChannel.configureBlocking(false)
            var clinetSenderAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress
            var forwardSenderAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress
            // Endlosschleife zum kontinuierlichen Empfangen von Paketen
            val buffer = ByteBuffer.allocate(MAX_PACKET_SIZE)
            val receiveBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE)

            while (true) {
                buffer.clear()
                val senderAddress = channel.receive(buffer)
                // Weiterleiten des empfangenen Pakets an das Ziel, wenn senderAddress nicht null ist
                senderAddress?.let {
                    //Log.d(TAG, "empfangenen Daten $senderAddress. ${buffer}")
                    buffer.flip()
                    clinetSenderAddress = senderAddress// App crash without this
                    forwardChannel.send(buffer, forwardSenderAddress)
                }

                receiveBuffer.clear()
                var forwardSenderAddress = forwardChannel.receive(receiveBuffer)
                forwardSenderAddress.let {
                    //Log.d(TAG, "empfangenen Daten $forwardSenderAddress. ${receiveBuffer} $clinetSenderAddress")
                    receiveBuffer.flip()
                    channel.send(receiveBuffer, clinetSenderAddress)
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
            Log.d(TAG, "Error occurred")
        }
    }

    private fun forwardPacket(buffer: ByteBuffer, senderAddress: InetSocketAddress, clinetSenderAddress :SocketAddress) {
        try {
            // Erstelle einen neuen DatagramChannel für den Weiterleitungsprozess
            Log.d(TAG, "empfangenen Daten $senderAddress.$clinetSenderAddress")

            val forwardChannel = DatagramChannel.open()
            forwardChannel.socket().setReceiveBufferSize(MAX_PACKET_SIZE)
            forwardChannel.socket().setSoTimeout(1000)
            forwardChannel.configureBlocking(true)
            // Verbinde den Weiterleitungs-Kanal mit der Zieladresse und dem Zielport
            //forwardChannel.isConnected.let {
            forwardChannel.connect(InetSocketAddress(DESTINATION_IP, DESTINATION_PORT))
            //}

            // Sende das Paket an das Ziel
            forwardChannel.write(buffer)
            val receiveBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE)

          //  while (true) {
                receiveBuffer.clear()
                val senderAddress = forwardChannel.receive(receiveBuffer)
                // Überprüfen, ob senderAddress nicht null ist und dann die Schleife verlassen
                if (senderAddress != null) {
                    Log.d(TAG, "empfangenen Daten $senderAddress. ${receiveBuffer} $clinetSenderAddress")
                    receiveBuffer.flip()
                    channel.send(receiveBuffer, clinetSenderAddress)
                    //channel.close()
                    //forwardChannel.connect(clinetSenderAddress)
                    /*val forwardChanneltest = DatagramChannel.open()
                    forwardChanneltest.send(receiveBuffer, clinetSenderAddress)
                    forwardChanneltest.close()*/
                    //break // Die Schleife verlassen, sobald senderAddress nicht null ist
                }
           // }

            buffer.clear()
            // Schließe den Kanal nach dem Senden des Pakets
            forwardChannel.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun forwardPacketold(buffer: ByteBuffer, senderAddress: InetSocketAddress, clientSenderAddress: SocketAddress) {
        try {
            // Überprüfen, ob die Größe des zu sendenden Datagramms die maximale Größe überschreitet
            if (buffer.remaining() > MAX_DATAGRAM_SIZE) {
                /*Log.e(TAG, "Message too long, splitting into smaller datagrams")
                // Wenn ja, das Datagramm in kleinere Teile aufteilen und nacheinander senden
                val remaining = buffer.remaining()
                var offset = 0
                while (offset < remaining) {
                    val chunkSize = min(MAX_DATAGRAM_SIZE, remaining - offset)
                    val chunkBuffer = ByteBuffer.allocate(chunkSize)
                    // Chunk des Datagrams kopieren
                    for (i in 0 until chunkSize) {
                        chunkBuffer.put(buffer.get())
                    }
                    // Chunk-Buffer für das Senden vorbereiten
                    chunkBuffer.flip()
                    // Datagramm senden
                    sendChunk(chunkBuffer, senderAddress as InetSocketAddress, clinetSenderAddress as InetSocketAddress)
                    offset += chunkSize
                }
                */
            } else {
                // Wenn die Größe des Datagramms innerhalb des zulässigen Bereichs liegt, es einfach senden
                sendChunk(buffer, senderAddress, clientSenderAddress)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    private fun sendChunk(chunkBuffer: ByteBuffer, senderAddress: InetSocketAddress, clientSenderAddress: SocketAddress) {
        val forwardChannel = DatagramChannel.open()
        try {
            // Verbinde den Weiterleitungs-Kanal mit der Zieladresse und dem Zielport
            forwardChannel.connect(InetSocketAddress(DESTINATION_IP, DESTINATION_PORT))
            // Senden des Datagrams
            forwardChannel.write(chunkBuffer)

            // Empfangen einer Antwort, falls erforderlich
            val receiveBuffer = ByteBuffer.allocate(MAX_DATAGRAM_SIZE)
            val receivedAddress = forwardChannel.receive(receiveBuffer)
            receivedAddress?.let {
                Log.d(TAG, "empfangenen Daten $senderAddress. ${receiveBuffer} $clientSenderAddress")

                // Datagramm an den ursprünglichen Client senden
                receiveBuffer.flip()
                channel.send(receiveBuffer, clientSenderAddress)
            }
            //forwardChannel.close()
        }
        finally {
            // Schließen des Weiterleitungs-Kanals
            forwardChannel.close()
        }
    }

    class PacketForwarder(private val localPort: Int, private val destinationIp: String, private val destinationPort: Int) : Runnable {
        private lateinit var serverSocket: DatagramSocket
        private lateinit var clientSocket: DatagramSocket
        private lateinit var destinationAddress: InetAddress

        init {
            try {
                serverSocket = DatagramSocket(localPort, InetAddress.getByName("0.0.0.0"))
                clientSocket = DatagramSocket()
                destinationAddress = InetAddress.getByName(destinationIp)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        override fun run() {
            try {
                val receiveBuffer = ByteArray(65507) // Maximum UDP packet size
                val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)

                while (true) {
                    serverSocket.setSoTimeout(100)
                    serverSocket.receive(receivePacket)
                    forwardPacket(receivePacket)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                serverSocket.close()
                clientSocket.close()
            }
        }

        private fun forwardPacket(packet: DatagramPacket) {
            try {
                val sendPacket = DatagramPacket(packet.data, packet.length, destinationAddress, destinationPort)
                clientSocket.setSoTimeout(100)
                clientSocket.send(sendPacket)

                // Receive response from destination server
                val responseBuffer = ByteArray(65507) // Maximum UDP packet size
                val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
                clientSocket.receive(responsePacket)

                // Send response back to original client
                val responseToClient = DatagramPacket(responseBuffer, responsePacket.length, packet.address, packet.port)
                serverSocket.send(responseToClient)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    class UdpForwarderTask(private val from: InetSocketAddress, private val to: InetSocketAddress, private val ruleName: String) : AsyncTask<Void, Void, Void>() {

        private lateinit var udpForwarder: UdpForwarder

        override fun doInBackground(vararg params: Void?): Void? {
            // Erstellen Sie den UDP-Forwarder und führen Sie ihn aus
            udpForwarder = UdpForwarder(from, to, ruleName)
            udpForwarder.call()
            return null
        }

        override fun onCancelled() {
            // Beenden Sie den Forwarder, wenn die Aufgabe abgebrochen wird
            //udpForwarder.interrupt()
        }
    }

    class UdpForwarder(private val from: InetSocketAddress, private val to: InetSocketAddress, private val ruleName: String) :
        Callable<Void> {

        private val TAG = "UdpForwarder"
        private val BUFFER_SIZE = 100000
        private val TIMEOUT = 3000 // Wartezeit (Millisekunden)

        @Throws(IOException::class, BindException::class)
        override fun call(): Void? {
            Log.d(TAG, "Starte UDP-Weiterleitung von ${from.port} zu ${to.port}")

            try {
                val readBuffer = ByteBuffer.allocate(BUFFER_SIZE)

                // DatagramChannel für eingehende Daten öffnen und konfigurieren
                val inChannel = DatagramChannel.open()
                inChannel.configureBlocking(false)
                try {
                    inChannel.socket().bind(from)
                } catch (e: SocketException) {
                    Log.e(TAG, "Binden des Sockets fehlgeschlagen für Port ${from.port}", e)
                    throw BindException("Binden des Sockets fehlgeschlagen für Port ${from.port}")
                }

                // Selector für I/O-Operationen erstellen
                val selector = Selector.open()
                inChannel.register(selector, SelectionKey.OP_READ, ClientRecord(to))

                while (true) {
                    if (Thread.currentThread().isInterrupted) {
                        Log.i(TAG, "Thread wurde unterbrochen, Aufräumen...")
                        inChannel.socket().close()
                        break
                    }

                    if (selector.select() > 0) {
                        val keyIter = selector.selectedKeys().iterator()
                        while (keyIter.hasNext()) {
                            val key = keyIter.next()
                            if (key.isReadable) {
                                handleRead(key, readBuffer)
                            }
                            if (key.isValid && key.isWritable) {
                                handleWrite(key)
                            }
                            keyIter.remove()
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Problem beim Öffnen des Selectors", e)
                throw e
            }
            return null
        }

        @Throws(IOException::class)
        private fun handleRead(key: SelectionKey, readBuffer: ByteBuffer) {
            val channel = key.channel() as DatagramChannel
            val clientRecord = key.attachment() as ClientRecord

            readBuffer.clear()
            channel.receive(readBuffer)
            readBuffer.flip()
            channel.send(readBuffer, clientRecord.toAddress)

            if (readBuffer.remaining() > 0) {
                clientRecord.writeBuffer.put(readBuffer)
                key.interestOps(SelectionKey.OP_WRITE)
            }
        }

        @Throws(IOException::class)
        private fun handleWrite(key: SelectionKey) {
            val channel = key.channel() as DatagramChannel
            val clientRecord = key.attachment() as ClientRecord

            clientRecord.writeBuffer.flip()
            val bytesSent = channel.send(clientRecord.writeBuffer, clientRecord.toAddress)

            if (clientRecord.writeBuffer.remaining() > 0) {
                clientRecord.writeBuffer.compact()
            } else {
                key.interestOps(SelectionKey.OP_READ)
                clientRecord.writeBuffer.clear()
            }
        }

        internal class ClientRecord(val toAddress: InetSocketAddress) {
            val writeBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE)
        }
    }

    class UdpForwarderold(private val fromAddress: InetSocketAddress, private val toAddress: InetSocketAddress) {

        private val bufferSize = 100000
        private val selector = Selector.open()
        private val readBuffer = ByteBuffer.allocate(bufferSize)
        private val writeBuffer = ByteBuffer.allocate(bufferSize)

        init {
            val channel = DatagramChannel.open()
            channel.socket().bind(fromAddress)
            channel.configureBlocking(false)
            channel.register(selector, SelectionKey.OP_READ)
        }

        fun start() {
            while (true) {
                selector.select()
                val keys = selector.selectedKeys()
                val iterator = keys.iterator()
                while (iterator.hasNext()) {
                    val key = iterator.next()
                    if (key.isReadable) {
                        handleRead(key)
                    } else if (key.isWritable) {
                        handleWrite(key)
                    }
                    iterator.remove()
                }
            }
        }

        private fun handleRead(key: SelectionKey) {
            val channel = key.channel() as DatagramChannel
            readBuffer.clear()
            val fromAddress = channel.receive(readBuffer) as InetSocketAddress
            readBuffer.flip()
            val data = ByteArray(readBuffer.remaining())
            readBuffer.get(data)
            writeBuffer.clear()
            writeBuffer.put(data)
            writeBuffer.flip()
            val toChannel = DatagramChannel.open()
            toChannel.send(writeBuffer, toAddress)
            toChannel.close()
            writeBuffer.clear()
        }

        private fun handleWrite(key: SelectionKey) {
            // Not used in this implementation
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Schließe den Kanal, wenn die App zerstört wird
        //channel.close()
    }
}
