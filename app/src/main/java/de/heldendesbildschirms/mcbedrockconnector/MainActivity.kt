package de.heldendesbildschirms.mcbedrockconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.*
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.io.*
import java.lang.Thread.sleep
import java.net.*
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.Executors
import kotlin.system.exitProcess

object ServerState { // If another solution is to be found, it won't work this way either.
    var isServerThreadRunning = false
    var isServerThreadRunningInfo1 = false
    var isServerThreadRunningInfo2 = false
}

class MainActivity : AppCompatActivity() {

    private lateinit var channel: DatagramChannel

    private companion object {
        private const val TAG = "MainActivity"
        private const val SOURCE_PORT = 19132
        private var DESTINATION_IP = "104.238.130.180"
        private var DESTINATION_PORT = 19132
        private const val REQUEST_INTERNET_PERMISSION = 123
        private const val MAX_PACKET_SIZE = 65507
        private const val MAX_DATAGRAM_SIZE = 65507
        private const val PREF_NAME = "MyPrefs"
        private const val PREF_DESTINATION_IP = "destination_ip"
        private const val PREF_DESTINATION_PORT = "destination_port"
        var isServerThreadRunning = ServerState.isServerThreadRunning
        var isServerThreadRunningInfo1 = ServerState.isServerThreadRunningInfo1
        var isServerThreadRunningInfo2 = ServerState.isServerThreadRunningInfo2
    }

    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var myWakeLock: MyWakeLock
    private val CHANNEL_ID = "MC_Bedroock_Connect_Channel"
    var mNotificationManager: NotificationManager? = null

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        //myWakeLock = MyWakeLock(this)
        //.acquireWakeLock()
        val intent = Intent()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            intent.action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS;
            intent.data = Uri.parse("package:" + packageName);
            this.startActivity(intent);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            Toast.makeText(this, "Not allowed isIgnoringBatteryOptimizations", Toast.LENGTH_SHORT)
                .show()

            val notificationBuilder =
                NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle(CHANNEL_ID)
                    .setContentText("Background activity is restricted on this device.")
                    .setSmallIcon(R.drawable.ic_launcher_foreground).setAutoCancel(true)

            val notificationIntent = Intent(Settings.ACTION_APPLICATION_SETTINGS)
            val pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

            val expandedNotificationText = """
        Background activity is restricted on this app.
        Please allow it so we can post an active notification during work sessions.
        
        To do so, click on the notification to go to
        App management -> search for ${getString(R.string.app_name)} -> Battery Usage -> enable 'Allow background activity'
    """.trimIndent()

            notificationBuilder.setContentIntent(pendingIntent)
                .setStyle(NotificationCompat.BigTextStyle().bigText(expandedNotificationText))

            createChannel()

            val notification = notificationBuilder.build()

            mNotificationManager?.notify(10000, notification)
        } else {
            //intent.action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS;
        }
        //this.startActivity(intent);


        // Überprüfen, ob die Berechtigung zur Laufzeit angefordert werden muss
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.INTERNET
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // Berechtigung zur Laufzeit anfordern
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.INTERNET), REQUEST_INTERNET_PERMISSION
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

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        // Load destination IP and port from SharedPreferences or use default values
        val destinationIP = sharedPreferences.getString(PREF_DESTINATION_IP, DESTINATION_IP)
        val destinationPort = sharedPreferences.getInt(PREF_DESTINATION_PORT, DESTINATION_PORT)

        // Set EditText fields to loaded values
        findViewById<EditText>(R.id.editTextHostname).setText(destinationIP)
        findViewById<EditText>(R.id.editTextPort).setText(destinationPort.toString())
        findViewById<Button>(R.id.buttonConfirm).setOnClickListener {
            val hostname =
                if (findViewById<EditText>(R.id.editTextHostname).text.toString().isNotEmpty()) {
                    findViewById<EditText>(R.id.editTextHostname).text.toString()
                } else {
                    DESTINATION_IP
                }
            val port = if (findViewById<EditText>(R.id.editTextPort).text.toString().isNotEmpty()) {
                if (findViewById<EditText>(R.id.editTextPort).text.toString().toInt() <= 65535) {
                    findViewById<EditText>(R.id.editTextPort).text.toString().toInt()
                } else {
                    SOURCE_PORT
                }
            } else {
                SOURCE_PORT
            }

            // Convert hostname to IP address
            Thread {
                val ip = resolveIPAddress(hostname)

                // Update the destination IP and port
                DESTINATION_IP = ip
                DESTINATION_PORT = port

                saveDestinationSettings(hostname, port)
                forwardSenderAddress = InetSocketAddress(
                    DESTINATION_IP, DESTINATION_PORT
                ) //Change the global variable from within a thread without restarting the socket.
            }.start()
        }

        findViewById<Button>(R.id.exit).setOnClickListener {
            isServerThreadRunning = false
            executorService.shutdown()
            finish()
            exitProcess(0)
        }

        findViewById<Button>(R.id.start_stop).setOnClickListener {
            Thread {
                if (isServerThreadRunningInfo1 && isServerThreadRunningInfo2) {
                    isServerThreadRunning = false
                    while (isServerThreadRunningInfo1 && isServerThreadRunningInfo2) {
                        sleep(1000)
                        isServerThreadRunning = false
                        Log.e(TAG, "Wait for Stop Server")
                    }
                    findViewById<Button>(R.id.start_stop).text = "Start"
                } else {
                    startnewUDPServerThreads()
                    findViewById<Button>(R.id.start_stop).text = "Stop"
                }
            }.start()
        }

        findViewById<Button>(R.id.restartServer).setOnClickListener {
            Thread {
                isServerThreadRunning = false
                while (isServerThreadRunningInfo1 && isServerThreadRunningInfo2) {
                    sleep(1000)
                    isServerThreadRunning = false
                    Log.e(TAG, "Wait for Stop Server")
                }
                startnewUDPServerThreads()
                findViewById<Button>(R.id.start_stop).text = "Stop"
            }.start()
        }

        val listView = findViewById<ListView>(R.id.serverList)

        val itemsServerIp = mutableListOf<String>()
        val itemsServerPort = mutableListOf<Int>()
        val serverListAdapter =
            ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, itemsServerIp)

        //val destinationIPSave = sharedPreferences.getString("PREF_DESTINATION_IPS", "")
        //val destinationIPSet = sharedPreferences.getStringSet("PREF_DESTINATION_IPS", emptySet())
        val destinationPortSet =
            sharedPreferences.getStringSet("PREF_DESTINATION_PORTS", emptySet())
        //val destinationIPList = sharedPreferences.getStringSet("PREF_DESTINATION_IPS", setOf<String>())!!.toMutableList()
        //val destinationIPList = sharedPreferences.getStringSet("PREF_DESTINATION_IPS", emptySet())?.toMutableList() ?: mutableListOf()

        // Retrieve the Set of strings from SharedPreferences
        val mySet = sharedPreferences.getStringSet("PREF_DESTINATION_IPS", emptySet())

        // Convert the Set of strings back to an ArrayList
        val destinationIPListA = arrayListOf<String>()
        destinationIPListA.addAll(mySet!!)
        var destinationIPList = destinationIPListA.toMutableList()
        val itemsIpAndPort = destinationIPList
        //val destinationIPList = destinationIPSave?.toMutableList()?: mutableListOf()
        //val destinationIPList = destinationIPSet?.toMutableList()
        //val destinationIPList = destinationIPSet?.toMutableList() ?: mutableListOf()
        val destinationPortList =
            destinationPortSet?.map { it.toInt() }?.toMutableList() ?: mutableListOf()

        for (combined in destinationIPList) {
            val lastColonIndex = combined.lastIndexOf(":")
            if (lastColonIndex != -1) {
                val ip = combined.substring(0, lastColonIndex)
                val port = combined.substring(lastColonIndex + 1)
                itemsServerIp.add(ip)
                itemsServerPort.add(port.toInt())
            }
        }


        //itemsServerIp.addAll(destinationIPList)
        //itemsServerPort.addAll(destinationPortList)
        /*if (destinationIPList.isNotEmpty()) {
            for (ip in destinationIPList) {
                itemsServerIp.add(ip.toString())
            }
        }
        if (destinationPortList.isNotEmpty()) {
            for (port in destinationPortList) {
                itemsServerPort.add(port)
            }
        }*/
        listView.adapter = serverListAdapter

        listView.setOnItemClickListener { parent, view, i, id ->
            findViewById<EditText>(R.id.editTextHostname).setText(itemsServerIp.get(i))
            findViewById<EditText>(R.id.editTextPort).setText(itemsServerPort.get(i).toString())
            Toast.makeText(this, "Clicked item : ${itemsServerIp.get(i)}", Toast.LENGTH_SHORT)
                .show()

        }

        listView.setOnItemLongClickListener { parent, view, i, id ->
            var hostname = "${itemsServerIp[i]}:${itemsServerPort[i].toString()}"
            Toast.makeText(this, "Remove: ${itemsServerIp.get(i)}", Toast.LENGTH_SHORT).show()
            itemsServerIp.removeAt(i)
            itemsServerPort.removeAt(i)
            serverListAdapter.notifyDataSetChanged()

            val editor = sharedPreferences.edit()
            itemsIpAndPort.remove(hostname)
            /*if (destinationIPList.isNotEmpty()) {
                for (ia in itemsIpAndPort.indices) {
                    if (itemsIpAndPort[ia] == hostname) {
                        itemsIpAndPort.removeAt(hostname)
                        break
                    }
                }
            }*/
            editor.putStringSet("PREF_DESTINATION_IPS", itemsIpAndPort.toSet())
            editor.apply()
            false
        }

        findViewById<FloatingActionButton>(R.id.addServer).setOnClickListener {
            itemsServerIp.add("$DESTINATION_IP")
            itemsServerPort.add(DESTINATION_PORT)
            listView.adapter = serverListAdapter

            val editor = sharedPreferences.edit()
            for (i in itemsServerIp.indices) {
                val combined = "${itemsServerIp[i]}:${itemsServerPort[i].toString()}"
                itemsIpAndPort.add(combined)
            }
            editor.putStringSet("PREF_DESTINATION_IPS", itemsIpAndPort.toSet())
            //editor.putStringSet("PREF_DESTINATION_IPS", myArrayList.toTypedArray().toList().toSet()) //Ob Array oder List oder sonst was, toSet geht nicht nur String. toSet sortiert auch wohl doppelte Einträge raus, weil Hash, deshalb geht es nicht.
            editor.putStringSet(
                "PREF_DESTINATION_PORTS", itemsServerPort.map { it.toString() }.toSet()
            )
            //val destinationPortSettest = sharedPreferences.getString("PREF_DESTINATION_IPSTest", "")
            //Toast.makeText(this, "Long clicked item : ${itemsIpAndPort.toSet()}", Toast.LENGTH_SHORT).show()
            editor.apply()
        }

        /*val fromPort = 19132
        val toIp = "164.68.125.80"  // Zielserver IP
        val toPort = 19132
        Thread {
            val forwarder = UdpForwarder(fromPort, toIp, toPort)
            forwarder.start()
        }.start()

        val forwarder = UdpForwarder()

        Thread {
            forwarder.startForwarding(
                localPort = 19132,
                remoteIp = "164.68.125.80",
                remotePort = 19132
            )
        }
        */
        /*Thread {
            udpForwarder.startForwarding(SOURCE_PORT, DESTINATION_IP, DESTINATION_PORT)
        }.start()*/

        if (!(isServerThreadRunningInfo1 && isServerThreadRunningInfo2)) {
            startnewUDPServerThreads()
            findViewById<Button>(R.id.start_stop).text = "Stop"
        }

        //startSocat(SOURCE_PORT,DESTINATION_IP,DESTINATION_PORT);

        //Thread {
        //startnewUDPServer()
        //startOptimizedUDPServer()
        //startnewUDPServer()
        //startUDPServer()
        //startUDPServersThreads() //Das kann zwar alles beschleunigen, crasht aber, weill der Socket nicht empfangen und senden gleichzeitig kann und hier wird ein eigener erstellt in jeder Funktion, also so geht das auch nicht.
        //}.start()

        if (isServerThreadRunningInfo1 && isServerThreadRunningInfo2) {
            findViewById<Button>(R.id.start_stop).text = "Stop"
        } else {
            findViewById<Button>(R.id.start_stop).text = "Start"
        }

        val isFirstStart = sharedPreferences.getBoolean("is_first_start", true)

        if (isFirstStart) {
            // Füge Elemente zur Liste hinzu
            itemsServerIp.add("$DESTINATION_IP")
            itemsServerPort.add(DESTINATION_PORT)
            listView.adapter = serverListAdapter

            val editor = sharedPreferences.edit()
            for (i in itemsServerIp.indices) {
                val combined = "${itemsServerIp[i]}:${itemsServerPort[i].toString()}"
                itemsIpAndPort.add(combined)
            }
            editor.putStringSet("PREF_DESTINATION_IPS", itemsIpAndPort.toSet())
            editor.putStringSet(
                "PREF_DESTINATION_PORTS", itemsServerPort.map { it.toString() }.toSet()
            )
            editor.putBoolean("is_first_start", false)
            editor.apply()
        }
    }

    private fun resolveIPAddress(hostname: String): String {
        return try {
            // Resolve hostname to IP address
            val inetAddress = InetAddress.getByName(hostname)
            inetAddress.hostAddress
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Error resolving hostname: ${e.message}")
            // Return default IP address if hostname resolution fails
            DESTINATION_IP
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Speichern der Variablen
        outState.putBoolean("isServerThreadRunning", isServerThreadRunning)
        outState.putBoolean("isServerThreadRunningInfo1", isServerThreadRunningInfo1)
        outState.putBoolean("isServerThreadRunningInfo2", isServerThreadRunningInfo2)
        ServerState.isServerThreadRunning = isServerThreadRunning
        ServerState.isServerThreadRunningInfo1 = isServerThreadRunningInfo1
        ServerState.isServerThreadRunningInfo2 = isServerThreadRunningInfo2
        Log.d(
            TAG, "Test2"
        )
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        // Wiederherstellen der Variablen
        isServerThreadRunning = savedInstanceState.getBoolean("isServerThreadRunning", false)
        isServerThreadRunningInfo1 =
            savedInstanceState.getBoolean("isServerThreadRunningInfo1", false)
        isServerThreadRunningInfo2 =
            savedInstanceState.getBoolean("isServerThreadRunningInfo2", false)
        Log.d(
            TAG, "Test1"
        )
    }

    private fun saveDestinationSettings(ip: String, port: Int) {
        // Save destination IP and port to SharedPreferences
        val editor = sharedPreferences.edit()
        editor.putString(PREF_DESTINATION_IP, ip)
        editor.putInt(PREF_DESTINATION_PORT, port)
        editor.apply()
    }

    class MyWakeLock(private val context: Context) {
        private var wakeLock: PowerManager.WakeLock? = null

        @SuppressLint("InvalidWakeLockTag")
        fun acquireWakeLock() {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyWakeLockTag")
            wakeLock?.acquire()
        }

        fun releaseWakeLock() {
            wakeLock?.release()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createChannel() {
        var mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val name = "MC Bedrock Connect"
        val importance = NotificationManager.IMPORTANCE_LOW

        val mChannel = NotificationChannel(CHANNEL_ID, name, importance)

        mChannel.name = "Notifications"

        mNotificationManager.createNotificationChannel(mChannel)
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
    private val packetServerThread = DatagramPacket(
        ByteArray(65000), 65000, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT
    )

    private fun startUDPServerThread() {
        var maxPaketSize = 65000
        var timeout = 300
        while (true) {
            try {
                serverSocketThread.soTimeout = timeout
                //val packet = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize)
                //if (clientSocketThread.isConnected){
                serverSocketThread.receive(packetServerThread)
                //val sendPacket = DatagramPacket(packet.data, packet.length, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)
                clientSocketThread.send(packetServerThread)
                Log.d(
                    TAG, "Senden die empfangenen Daten an den Zielserver und warte auf eine Antwort"
                )
                //}
            } catch (e: Exception) {
                //Log.d(TAG, "$e")
            } finally {
            }
        }
    }

    private fun startUDPClientThread() {
        var maxPaketSize = 65000
        var timeout = 300
        while (true) {
            Log.d(
                TAG, "Senden die empfangenen${packetServerThread.address}"
            ) //Nicht die gleiche Adreese.
            try {
                clientSocketThread.soTimeout = timeout
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
                Log.d(
                    TAG, "Senden die empfangenen Daten an den Zielserver und warte auf eine Antwort"
                )
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
        serverSocket.soTimeout = timeout
        val packet = DatagramPacket(ByteArray(maxPaketSize), maxPaketSize)
        serverSocket.receive(packet)
        serverSocket.send(startUDPClientThreadVorlage(packet))
    }

    private fun startUDPClientThreadVorlage(packet: DatagramPacket): DatagramPacket {
        var maxPaketSize = 65507
        var timeout = 300
        val clientSocket = DatagramSocket()
        clientSocket.soTimeout = timeout
        val sendPacket = DatagramPacket(
            packet.data, packet.length, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT
        )
        clientSocket.send(sendPacket)
        val receivePacket =
            DatagramPacket(ByteArray(maxPaketSize), maxPaketSize, packet.address, packet.port)
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
            val clientSocket = DatagramSocket()
            // Endlosschleife zum kontinuierlichen Empfangen von Paketen
            var maxPaketSize = 65000
            var timeout = 100
            //clientSocket.connect(InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT)

            var debugVar = 0
            while (true) {
                try {
                    debugVar = 0
                    serverSocket.soTimeout = timeout
                    serverSocket.receiveBufferSize = maxPaketSize
                    serverSocket.sendBufferSize = maxPaketSize
                    clientSocket.soTimeout = timeout
                    clientSocket.receiveBufferSize = maxPaketSize
                    clientSocket.sendBufferSize = maxPaketSize
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
                    val sendPacket = DatagramPacket(
                        packet.data,
                        packet.length,
                        InetAddress.getByName(DESTINATION_IP),
                        DESTINATION_PORT
                    )
                    //val hexSendData = sendData.joinToString(separator = " ") { byte -> String.format("%02X", byte) }
                    //Log.d(TAG, "Send UDP packet: $hexSendData")
                    //Thread { //<---
                    clientSocket.send(sendPacket)
                    //}.start()
                    // Empfange die Antwort vom Zielserver
                    //val receiveData = ByteArray(maxPaketSize)
                    val receivePacket = DatagramPacket(
                        ByteArray(maxPaketSize), maxPaketSize, packet.address, packet.port
                    )
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
                    Log.d(
                        TAG,
                        "Error UDP packet $debugVar ${serverSocket.isClosed} ${serverSocket.isConnected} ${clientSocket.isConnected}"
                    )
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
        val clientSocket = DatagramSocket()
        return try {
            // Erstelle einen Socket zum Senden von Daten an den Zielserver

            // Sende die Daten an den Zielserver
            val sendData = data
            val sendPacket = DatagramPacket(
                sendData, sendData.size, InetAddress.getByName(DESTINATION_IP), DESTINATION_PORT
            )
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
            clientSocket.close()
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
                toClientSocket.close()
            }
        }.start()
    }

    private fun ByteArray.toHexString(): String {
        return this.joinToString(separator = " ") { byte -> String.format("%02X", byte) }
    }

    var forwardSenderAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress

    private val executorService = Executors.newFixedThreadPool(2)

    private fun startnewUDPServerThreads() {
        isServerThreadRunning = true
        isServerThreadRunningInfo1 = true
        isServerThreadRunningInfo2 = true
        executorService.submit {
            val channel = DatagramChannel.open()
            val forwardChannel = DatagramChannel.open()
            var clinetSenderAddress =
                InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress

            /*
            // Thread für den Empfang von Daten
            val channel = DatagramChannel.open()
            val forwardChannel = DatagramChannel.open()
            //var forwardSenderAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress
            var clinetSenderAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress
             */

            val receiveThread = Thread {
                try {
                    channel.socket().bind(InetSocketAddress(SOURCE_PORT))
                    channel.socket().receiveBufferSize = MAX_PACKET_SIZE
                    channel.socket().sendBufferSize = MAX_PACKET_SIZE
                    //channel.configureBlocking(true)
                    //channel.socket().soTimeout = 100 //Does not work to prevent reception stop. For this to work at all, channel must be at 100 and forwardChannel must be at 1000.

                    val buffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE)

                    //forwardChannel.connect(forwardSenderAddress)
                    while (isServerThreadRunning) {
                        //buffer.clear()
                        val senderAddress = channel.receive(buffer)
                        senderAddress?.let {
                            // Starte die Zeitmessung
                            //val startTime = System.nanoTime()
                            clinetSenderAddress = senderAddress
                            buffer.flip()
                            //forwardChannel.write(buffer)
                            forwardChannel.send(buffer, forwardSenderAddress)
                            buffer.clear()
                            // Stoppe die Zeitmessung und berechne die Dauer
                            //val duration = System.nanoTime() - startTime
                            // Gib die Dauer in Millisekunden aus
                            //Log.d(TAG, "Client Paket gesendet in ${duration / 1_000_000} ms")
                        }
                        //sleep(1)
                    }
                    channel.close()
                    forwardChannel.close()
                    isServerThreadRunningInfo1 = false
                } catch (e: IOException) {
                    channel.close()
                    forwardChannel.close()
                    e.printStackTrace()
                    Log.d(TAG, "Error occurred")
                    isServerThreadRunningInfo1 = false
                }
            }
            receiveThread.start()

            // Thread für das Weiterleiten von Daten
            val forwardThread = Thread {
                try {
                    forwardChannel.socket().receiveBufferSize = MAX_PACKET_SIZE
                    forwardChannel.socket().sendBufferSize = MAX_PACKET_SIZE
                    //forwardChannel.configureBlocking(true)
                    //forwardChannel.socket().soTimeout = 1000

                    val receiveBuffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE)

                    while (isServerThreadRunning) {
                        //receiveBuffer.clear()
                        val forwardSenderAddressTemp = forwardChannel.receive(receiveBuffer)
                        forwardSenderAddressTemp?.let {
                            //forwardChannel.read(receiveBuffer)
                            //receiveBuffer?.let {
                            receiveBuffer.flip()

                            // Starte die Zeitmessung
                            //val startTime = System.nanoTime()

                            // Sende das Paket
                            channel.send(receiveBuffer, clinetSenderAddress)
                            receiveBuffer.clear()

                            // Stoppe die Zeitmessung und berechne die Dauer
                            //val duration = System.nanoTime() - startTime

                            // Gib die Dauer in Millisekunden aus
                            //Log.d(TAG, "Server Paket gesendet in ${duration / 1_000_000} ms")
                        }
                        //sleep(1)
                    }
                    channel.close()
                    forwardChannel.close()
                    isServerThreadRunningInfo2 = false
                } catch (e: IOException) {
                    channel.close()
                    forwardChannel.close()
                    e.printStackTrace()
                    Log.d(TAG, "Error occurred")
                    isServerThreadRunningInfo2 = false
                }
            }
            forwardThread.start()
        }
    }

    /*
    private fun startSocat(localPort: Int, remoteIp: String, remotePort: Int) {
        // 1. Kopiere die Binärdatei ins interne Verzeichnis
        /*val socatFile = File(filesDir, "socat")
        if (!socatFile.exists()) {
            applicationContext.assets.open("socat").use { input ->
                FileOutputStream(socatFile).use { output ->
                    input.copyTo(output)
                }
            }
            socatFile.setExecutable(true, false) // Datei ausführbar machen
            socatFile.setReadable(true, false)   // Datei lesbar machen
            socatFile.setWritable(true, false)   // Datei schreibbar machen (optional)
            Log.d(TAG, "Socat erfolgreich kopiert nach: ${socatFile.absolutePath}")
        }
        val assetFiles = assets.list("") // Listet alle Dateien im assets-Ordner
        Log.d("Assets", "Verfügbare Dateien: ${assetFiles?.joinToString()}")

        val files = File(filesDir.absolutePath).listFiles()
        if (files != null) {
            for (file in files) {
                Log.d("FilesDir", "Datei gefunden: ${file.name}")
            }
        } else {
            Log.d("FilesDir", "Keine Dateien gefunden.")
        }*/

/*
        // 2. Starte den socat-Prozess
        val socatPath = applicationInfo.nativeLibraryDir + "/socat.so"
        val command = arrayOf(
            //socatFile.absolutePath,
            socatPath,
            "UDP-LISTEN:$localPort,fork",
            "UDP:$remoteIp:$remotePort"
        )

        try {
            Runtime.getRuntime().exec(command)
            Log.d(TAG, "socat gestartet: ${command.joinToString(" ")}")
        } catch (e: IOException) {
            Log.e(TAG, "socat starten fehlgeschlagen", e)
        }
        */

        val socatPath = applicationInfo.nativeLibraryDir + "/socat.so"
        val command = arrayOf(
            socatPath,
            "UDP-LISTEN:$localPort,fork",
            "UDP:$remoteIp:$remotePort"
        )

        try {
            val process = ProcessBuilder(*command).start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            Thread {
                reader.forEachLine { Log.d(TAG, "SOCAT: $it") }
            }.start()

            Thread {
                errorReader.forEachLine { Log.e(TAG, "SOCAT ERROR: $it") }
            }.start()

            Log.d(TAG, "socat gestartet: ${command.joinToString(" ")}")

        } catch (e: IOException) {
            Log.e(TAG, "socat starten fehlgeschlagen", e)
        }

    }
     */
    private fun startnewUDPServer() {
        try {
            // Erstelle einen DatagramChannel zum Empfangen von UDP-Paketen auf dem Quellport
            val channel = DatagramChannel.open()
            channel.socket().bind(InetSocketAddress(SOURCE_PORT))
            channel.socket().receiveBufferSize = MAX_PACKET_SIZE
            channel.configureBlocking(false)

            val forwardChannel = DatagramChannel.open()
            forwardChannel.socket().receiveBufferSize = MAX_PACKET_SIZE
            forwardChannel.configureBlocking(false)
            var clinetSenderAddress =
                InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress
            //var forwardSenderAddress = InetSocketAddress(DESTINATION_IP, DESTINATION_PORT) as SocketAddress
            // Endlosschleife zum kontinuierlichen Empfangen von Paketen
            val buffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE)
            val receiveBuffer = ByteBuffer.allocateDirect(MAX_PACKET_SIZE)

            //forwardChannel.configureBlocking(true)
            //channel.configureBlocking(true)
            //forwardChannel.socket().soTimeout = 100
            //channel.socket().soTimeout = 1000

            while (true) {
                buffer.clear()
                val senderAddress = channel.receive(buffer)
                // Weiterleiten des empfangenen Pakets an das Ziel, wenn senderAddress nicht null ist
                senderAddress?.let {
                    //Log.d(TAG, "empfangenen Daten $senderAddress. ${buffer}")
                    clinetSenderAddress = senderAddress
                    buffer.flip()
                    forwardChannel.send(buffer, forwardSenderAddress)
                }

                receiveBuffer.clear()
                var forwardSenderAddressTemp = forwardChannel.receive(receiveBuffer)
                forwardSenderAddressTemp.let {
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

    /*
    // Buffer-Pool zur Vermeidung von Allokationen
    class BufferPool(poolSize: Int, factory: () -> ByteBuffer) {
        private val pool = ArrayDeque<ByteBuffer>(poolSize).apply {
            repeat(poolSize) { add(factory()) }
        }

        fun acquire(): ByteBuffer = synchronized(pool) {
            pool.removeFirst()
        }

        fun release(buffer: ByteBuffer) = synchronized(pool) {
            buffer.clear()
            pool.addLast(buffer)
        }
    }

    private fun forwardPacket(
        buffer: ByteBuffer,
        senderAddress: InetSocketAddress,
        clinetSenderAddress: SocketAddress
    ) {
        try {
            // Erstelle einen neuen DatagramChannel für den Weiterleitungsprozess
            Log.d(TAG, "empfangenen Daten $senderAddress.$clinetSenderAddress")

            val forwardChannel = DatagramChannel.open()
            forwardChannel.socket().receiveBufferSize = MAX_PACKET_SIZE
            forwardChannel.socket().soTimeout = 1000
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
                Log.d(
                    TAG,
                    "empfangenen Daten $senderAddress. ${receiveBuffer} $clinetSenderAddress"
                )
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

    private fun forwardPacketold(
        buffer: ByteBuffer,
        senderAddress: InetSocketAddress,
        clientSenderAddress: SocketAddress
    ) {
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

    private fun sendChunk(
        chunkBuffer: ByteBuffer,
        senderAddress: InetSocketAddress,
        clientSenderAddress: SocketAddress
    ) {
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
                Log.d(
                    TAG,
                    "empfangenen Daten $senderAddress. ${receiveBuffer} $clientSenderAddress"
                )

                // Datagramm an den ursprünglichen Client senden
                receiveBuffer.flip()
                channel.send(receiveBuffer, clientSenderAddress)
            }
            //forwardChannel.close()
        } finally {
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
                    serverSocket.soTimeout = 100
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
                clientSocket.soTimeout = 100
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
    */

    /*class UdpForwarderblödsinn(private val fromPort: Int, private val toIp: String, private val toPort: Int) {

        private val BUFFER_SIZE = 65535 // Maximale UDP-Paketgröße
        private val TIMEOUT_MS = 3000 // Timeout für inaktive Verbindungen

        fun start() {
            // Erstelle einen Selector, um mehrere Kanäle zu überwachen
            val selector = Selector.open()
            // Erstelle den Eingangskanal, der auf eingehende Pakete lauscht
            val inChannel = DatagramChannel.open()

            // Non-blocking Mode aktivieren
            inChannel.configureBlocking(false)
            inChannel.socket().bind(InetSocketAddress(fromPort))

            // Kanal am Selector registrieren (nur für Leseoperationen)
            inChannel.register(selector, SelectionKey.OP_READ)

            println("UDP Forwarder gestartet auf Port $fromPort -> $toIp:$toPort")

            // Buffer für das Empfangen und Weiterleiten von Daten
            val buffer = ByteBuffer.allocate(BUFFER_SIZE)

            while (true) {
                // Warten auf eingehende Pakete
                if (selector.select(TIMEOUT_MS.toLong()) == 0) {
                    continue // Keine neuen Pakete -> weiter warten
                }

                val keyIterator = selector.selectedKeys().iterator()
                while (keyIterator.hasNext()) {
                    val key = keyIterator.next()
                    keyIterator.remove()

                    if (key.isReadable) {
                        val clientChannel = key.channel() as DatagramChannel
                        buffer.clear()

                        // Empfange das Paket vom Client
                        val clientAddress = clientChannel.receive(buffer)
                        if (clientAddress != null) {
                            buffer.flip()
                            println("Empfangen von Client: $clientAddress (${buffer.limit()} Bytes)")

                            // Erstelle einen Kanal für die Weiterleitung des Pakets an den Zielserver
                            val forwardChannel = DatagramChannel.open()
                            forwardChannel.send(buffer, InetSocketAddress(toIp, toPort))
                            forwardChannel.close()

                            println("Weitergeleitet an: $toIp:$toPort")

                            // Jetzt auf die Antwort des Zielservers warten
                            buffer.clear()
                            val responseChannel = DatagramChannel.open()
                            responseChannel.socket().bind(null) // Binde an zufälligen Port

                            // Empfange Antwort vom Zielserver
                            val serverResponse = responseChannel.receive(buffer)
                            if (serverResponse != null) {
                                buffer.flip()
                                println("Antwort erhalten vom Zielserver.")

                                // Antwort zurück an den ursprünglichen Client senden
                                clientChannel.send(buffer, clientAddress)
                                println("Antwort an Client gesendet: $clientAddress")
                            }
                        }
                    }
                }
            }
        }
    }


    class UdpForwarderoldold(private val from: InetSocketAddress, private val to: InetSocketAddress, private val ruleName: String) :
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
    }*/

    /*class UdpForwarder {
        private val selector: Selector = Selector.open()
        private val clientToServerChannel: DatagramChannel = DatagramChannel.open()
        private val serverToClientChannel: DatagramChannel = DatagramChannel.open()
        private val bufferSize = 65507 // Max UDP-Paketgröße
        private var isRunning = true

        @Throws(IOException::class)
        fun startForwarding(localPort: Int, remoteIp: String, remotePort: Int) {
            configureChannels(localPort, remoteIp, remotePort)
            runEventLoop()
        }

        private fun configureChannels(localPort: Int, remoteIp: String, remotePort: Int) {
            // Client-to-Server Kanal (Empfängt Pakete vom Client)
            clientToServerChannel.apply {
                configureBlocking(false)
                bind(InetSocketAddress(localPort))
                register(selector, SelectionKey.OP_READ, "client_to_server")
            }

            // Server-to-Client Kanal (Sendet Pakete zum Server)
            serverToClientChannel.apply {
                configureBlocking(false)
                connect(InetSocketAddress(remoteIp, remotePort))
                register(selector, SelectionKey.OP_READ, "server_to_client")
            }
        }

        private fun runEventLoop() {
            while (isRunning) {
                selector.select(1000) // Timeout: 1 Sekunde
                val keys = selector.selectedKeys()
                val iterator = keys.iterator()

                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    when {
                        key.isReadable -> handleRead(key)
                        key.isWritable -> handleWrite(key) //handleWirte fehlt
                    }
                }
            }
        }

        private fun handleRead(key: SelectionKey) {
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val channel = key.channel() as DatagramChannel

            when (key.attachment()) {
                "client_to_server" -> {
                    val senderAddress = channel.receive(buffer) as InetSocketAddress
                    buffer.flip()
                    forwardToServer(buffer, senderAddress)
                }
                "server_to_client" -> {
                    channel.read(buffer)
                    buffer.flip()
                    forwardToClient(buffer, clientAddress) //hier fehlt doch die Adresse.
                }
            }
        }

        private fun forwardToServer(buffer: ByteBuffer, clientAddress: InetSocketAddress) {
            serverToClientChannel.write(buffer)
            Log.d(TAG, "Forwarded ${buffer.remaining()} bytes to server")
        }

        private fun forwardToClient(buffer: ByteBuffer, clientAddress) {
            // Hier müsste die Client-Adresse aus einem Session-Store abgerufen werden
            clientToServerChannel.send(buffer, clientAddress)
            Log.d(TAG, "Forwarded ${buffer.remaining()} bytes to client")
        }

        fun stop() {
            isRunning = false
            selector.wakeup()
            clientToServerChannel.close()
            serverToClientChannel.close()
        }
    }*/

    /*class UdpForwarder {
        private val selector: Selector = Selector.open()
        private val clientToServerChannel: DatagramChannel = DatagramChannel.open()
        private val serverToClientChannel: DatagramChannel = DatagramChannel.open()
        private val bufferSize = 65507
        private var isRunning = true

        // Session-Store für Client-Adressen
        private val clientSessions = ConcurrentHashMap<InetSocketAddress, Long>()

        @Throws(IOException::class)
        fun startForwarding(localPort: Int, remoteIp: String, remotePort: Int) {
            configureChannels(localPort, remoteIp, remotePort)
            runEventLoop()
        }

        private fun configureChannels(localPort: Int, remoteIp: String, remotePort: Int) {
            // Client-Kanal (Empfängt Pakete von Clients)
            clientToServerChannel.apply {
                configureBlocking(false)
                bind(InetSocketAddress(localPort))
                register(selector, SelectionKey.OP_READ, "client_to_server")
            }

            // Server-Kanal (Immer verbunden mit dem Zielserver)
            serverToClientChannel.apply {
                connect(InetSocketAddress(remoteIp, remotePort))
                configureBlocking(false)
                register(selector, SelectionKey.OP_READ, "server_to_client")
            }
        }

        private fun runEventLoop() {
            while (isRunning) {
                selector.select(1000)
                val keys = selector.selectedKeys()
                val iterator = keys.iterator()

                while (iterator.hasNext()) {
                    val key = iterator.next()
                    iterator.remove()

                    if (key.isReadable) handleRead(key)
                }
            }
        }

        private fun handleRead(key: SelectionKey) {
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val channel = key.channel() as DatagramChannel

            when (key.attachment()) {
                "client_to_server" -> handleClientToServer(channel, buffer)
                "server_to_client" -> handleServerToClient(channel, buffer)
            }
        }

        private fun handleClientToServer(channel: DatagramChannel, buffer: ByteBuffer) {
            val clientAddress = channel.receive(buffer) as InetSocketAddress
            buffer.flip()

            // Speichere Client-Adresse mit Zeitstempel
            clientSessions[clientAddress] = System.currentTimeMillis()

            forwardToServer(buffer)
            Log.d(TAG, "Forwarded ${buffer.remaining()} bytes to server from $clientAddress")
        }

        private fun handleServerToClient(channel: DatagramChannel, buffer: ByteBuffer) {
            channel.read(buffer)
            buffer.flip()

            // Finde die letzte aktive Client-Adresse
            val clientAddress = clientSessions.maxByOrNull { it.value }?.key
            clientAddress?.let {
                forwardToClient(buffer, it)
                Log.d(TAG, "Forwarded ${buffer.remaining()} bytes to client $it")
            }
        }

        private fun forwardToServer(buffer: ByteBuffer) {
            serverToClientChannel.write(buffer)
        }

        private fun forwardToClient(buffer: ByteBuffer, clientAddress: InetSocketAddress) {
            clientToServerChannel.send(buffer, clientAddress)
        }

        fun stop() {
            isRunning = false
            selector.wakeup()
            clientToServerChannel.close()
            serverToClientChannel.close()
        }
    }*/

    /*private fun startOptimizedUDPServer() {
    try {
        val selector = Selector.open()
        val channel = DatagramChannel.open().apply {
            bind(InetSocketAddress(SOURCE_PORT))
            configureBlocking(false)
            register(selector, SelectionKey.OP_READ)
        }

        val forwardChannel = DatagramChannel.open().apply {
            connect(InetSocketAddress(DESTINATION_IP, DESTINATION_PORT))
            configureBlocking(false)
        }

        channel.socket().receiveBufferSize = MAX_PACKET_SIZE
        forwardChannel.socket().receiveBufferSize = MAX_PACKET_SIZE

        val bufferPool = BufferPool(10) { ByteBuffer.allocateDirect(MAX_PACKET_SIZE) }
        val clientSessions = ConcurrentHashMap<InetSocketAddress, Long>()

        while (true) {
            selector.select(500) // Timeout: 500ms
            val keys = selector.selectedKeys().iterator()

            while (keys.hasNext()) {
                val key = keys.next()
                keys.remove()

                if (key.isReadable) {
                    val buffer = bufferPool.acquire()
                    val senderAddress = channel.receive(buffer) as InetSocketAddress
                    buffer.flip()

                    // Update client session
                    clientSessions[senderAddress] = System.currentTimeMillis()

                    // Forward to server
                    forwardChannel.write(buffer)
                    bufferPool.release(buffer)
                }
            }

            // Check for server responses
            val responseBuffer = bufferPool.acquire()
            while (forwardChannel.read(responseBuffer) > 0) {
                responseBuffer.flip()
                // Send to last active client (Beispiel)
                clientSessions.maxByOrNull { it.value }?.key?.let {
                    channel.send(responseBuffer, it)
                }
                responseBuffer.clear()
            }
            bufferPool.release(responseBuffer)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Server crashed: ${e.message}")
    }
}

    class UdpForwarder(
        private val context: Context,
        private val localPort: Int,
        private val remoteIp: String,
        private val remotePort: Int
    ) : CoroutineScope {

        private val job = Job()
        override val coroutineContext: CoroutineContext = Dispatchers.IO + job

        private val selector: Selector = Selector.open()
        private val clientChannel: DatagramChannel = DatagramChannel.open().apply {
            configureBlocking(false)
            bind(InetSocketAddress(localPort))
            register(selector, SelectionKey.OP_READ)
        }

        private val serverChannel: DatagramChannel = DatagramChannel.open().apply {
            configureBlocking(false)
            connect(InetSocketAddress(remoteIp, remotePort))
        }

        // Session-Store: Client-Adresse -> Letzte Aktivität
        private val clientSessions = ConcurrentHashMap<InetSocketAddress, Long>()

        // Buffer-Pool zur Vermeidung von Allokationen
        private val bufferPool = BufferPool(10) { ByteBuffer.allocateDirect(65507) }

        fun start() {
            launch {
                while (isActive) {
                    try {
                        selector.select(500) // Timeout: 500ms
                        val keys = selector.selectedKeys().iterator()

                        while (keys.hasNext()) {
                            val key = keys.next()
                            keys.remove()

                            if (key.isReadable) {
                                handleClientPacket()
                            }
                        }

                        handleServerResponses()
                        cleanupSessions()

                    } catch (e: Exception) {
                        Log.e(TAG, "Fehler im Selector-Loop", e)
                    }
                }
            }
        }

        private fun handleClientPacket() {
            val buffer = bufferPool.acquire()
            val clientAddress = clientChannel.receive(buffer) as? InetSocketAddress

            clientAddress?.let {
                buffer.flip()
                clientSessions[it] = System.currentTimeMillis()
                forwardToServer(buffer)
                bufferPool.release(buffer)
            }
        }

        private fun handleServerResponses() {
            val buffer = bufferPool.acquire()
            while (serverChannel.read(buffer) > 0) {
                buffer.flip()
                clientSessions.maxByOrNull { it.value }?.key?.let { clientAddress ->
                    forwardToClient(buffer, clientAddress)
                }
                buffer.clear()
            }
            bufferPool.release(buffer)
        }

        private fun forwardToServer(buffer: ByteBuffer) {
            serverChannel.write(buffer)
        }

        private fun forwardToClient(buffer: ByteBuffer, clientAddress: InetSocketAddress) {
            clientChannel.send(buffer, clientAddress)
        }

        private fun cleanupSessions() {
            val now = System.currentTimeMillis()
            clientSessions.entries.removeIf { (_, lastActive) ->
                now - lastActive > 300_000 // 5 Minuten Inaktivität
            }
        }

        fun stop() {
            job.cancel()
            clientChannel.close()
            serverChannel.close()
            selector.close()
        }

        // Buffer-Pool für effiziente Speichernutzung
        private class BufferPool(
            private val capacity: Int,
            private val factory: () -> ByteBuffer
        ) {
            private val pool = ArrayDeque<ByteBuffer>(capacity).apply {
                repeat(capacity) { add(factory()) }
            }

            fun acquire(): ByteBuffer = synchronized(pool) {
                pool.removeFirst()
            }

            fun release(buffer: ByteBuffer) = synchronized(pool) {
                buffer.clear()
                pool.addLast(buffer)
            }
        }

        companion object {
            private const val TAG = "UdpForwarder"
        }
    }
     */
}
