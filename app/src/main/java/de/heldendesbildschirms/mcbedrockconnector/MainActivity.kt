package de.heldendesbildschirms.mcbedrockconnector

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
import android.util.Log
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private companion object {
        private const val TAG = "MainActivity"
        private const val SOURCE_PORT = 19132
        private var DESTINATION_IP = "104.238.130.180"
        private var DESTINATION_PORT = 19132
        private const val REQUEST_INTERNET_PERMISSION = 123
        private const val REQUEST_NOTIFICATION_PERMISSION = 124
        private const val PREF_NAME = "MyPrefs"
        private const val PREF_DESTINATION_IP = "destination_ip"
        private const val PREF_DESTINATION_PORT = "destination_port"
        private const val PREF_EXPERIMENTAL_LAN_BRIDGE = "experimental_lan_bridge"
        private const val CHANNEL_ID = "MC_Bedroock_Connect_Channel"
    }

    private lateinit var sharedPreferences: SharedPreferences
    private var mNotificationManager: NotificationManager? = null

    @SuppressLint("BatteryLife", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mNotificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionsToRequest = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_LOCAL_NETWORK,
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
            }
            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(
                    this,
                    permissionsToRequest.toTypedArray(),
                    REQUEST_NOTIFICATION_PERMISSION,
                )
            }
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = "package:$packageName".toUri()
            }
            startActivity(intent)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Toast.makeText(this, "Not allowed isIgnoringBatteryOptimizations", Toast.LENGTH_SHORT)
                .show()

            val notificationBuilder =
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(getString(R.string.app_name))
                    .setContentText("Background activity is restricted on this device.")
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setAutoCancel(true)

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
        }

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.INTERNET,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.INTERNET),
                REQUEST_INTERNET_PERMISSION,
            )
        }

        sharedPreferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE)

        val destinationIP = sharedPreferences.getString(PREF_DESTINATION_IP, DESTINATION_IP) ?: DESTINATION_IP
        val destinationPort = sharedPreferences.getInt(PREF_DESTINATION_PORT, DESTINATION_PORT)
        val experimentalLanEnabled = sharedPreferences.getBoolean(PREF_EXPERIMENTAL_LAN_BRIDGE, false)
        DESTINATION_IP = destinationIP
        DESTINATION_PORT = destinationPort
        BedrockProxyManager.init(applicationContext)
        BedrockProxyManager.setExperimentalLanBridge(experimentalLanEnabled)

        val editHostname = findViewById<EditText>(R.id.editTextHostname)
        val editPort = findViewById<EditText>(R.id.editTextPort)
        val buttonStartStop = findViewById<Button>(R.id.start_stop)
        val buttonConfirm = findViewById<Button>(R.id.buttonConfirm)
        val buttonRestart = findViewById<Button>(R.id.restartServer)
        val buttonExit = findViewById<Button>(R.id.exit)
        val checkBoxExperimentalLan = findViewById<CheckBox>(R.id.checkBoxExperimentalLan)

        editHostname.setText(destinationIP)
        editPort.setText(destinationPort.toString())
        checkBoxExperimentalLan.isChecked = experimentalLanEnabled

        checkBoxExperimentalLan.setOnCheckedChangeListener { _, isChecked ->
            sharedPreferences.edit {
                putBoolean(PREF_EXPERIMENTAL_LAN_BRIDGE, isChecked)
            }
            BedrockProxyManager.setExperimentalLanBridge(isChecked)
            Toast.makeText(
                this,
                if (isChecked) "Experimental LAN Bridge enabled" else "Experimental LAN Bridge disabled",
                Toast.LENGTH_SHORT,
            ).show()
        }

        buttonConfirm.setOnClickListener {
            val hostname = editHostname.text.toString().trim().ifEmpty {
                DESTINATION_IP
            }
            val portInput = editPort.text.toString().trim().toIntOrNull()
            val port = if ((portInput != null) && (portInput in 1..65535)) {
                portInput
            } else {
                SOURCE_PORT
            }

            Thread {
                val ip = resolveIPAddress(hostname)
                DESTINATION_IP = ip
                DESTINATION_PORT = port
                saveDestinationSettings(hostname, port)
                BedrockProxyManager.updateDestination(ip, port, resetConnections = true)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "Target updated: $hostname:$port",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }.start()
        }

        buttonExit.setOnClickListener {
            BedrockProxyManager.stop()
            finish()
            exitProcess(0)
        }

        buttonStartStop.setOnClickListener {
            if (BedrockProxyManager.isProxyRunning()) {
                BedrockProxyManager.stop {
                    runOnUiThread {
                        buttonStartStop.text = "Start"
                    }
                }
            } else {
                BedrockProxyManager.start(DESTINATION_IP, DESTINATION_PORT, SOURCE_PORT) {
                    runOnUiThread {
                        buttonStartStop.text = "Stop"
                    }
                }
            }
        }

        buttonRestart.setOnClickListener {
            BedrockProxyManager.restart(DESTINATION_IP, DESTINATION_PORT, SOURCE_PORT) {
                runOnUiThread {
                    buttonStartStop.text = "Stop"
                    Toast.makeText(this, "UDP & TCP Proxy restarted", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val listView = findViewById<ListView>(R.id.serverList)

        val itemsServerIp = mutableListOf<String>()
        val itemsServerPort = mutableListOf<Int>()
        val serverListAdapter =
            ArrayAdapter(this, android.R.layout.simple_list_item_1, itemsServerIp)

        val mySet = sharedPreferences.getStringSet("PREF_DESTINATION_IPS", emptySet()) ?: emptySet()
        val itemsIpAndPort = mySet.toMutableList()

        for (combined in itemsIpAndPort) {
            val lastColonIndex = combined.lastIndexOf(":")
            if (lastColonIndex != -1) {
                val ip = combined.substring(0, lastColonIndex)
                val port = combined.substring(lastColonIndex + 1).toIntOrNull() ?: SOURCE_PORT
                itemsServerIp.add(ip)
                itemsServerPort.add(port)
            }
        }

        listView.adapter = serverListAdapter

        listView.setOnItemClickListener { _, _, i, _ ->
            editHostname.setText(itemsServerIp[i])
            editPort.setText(itemsServerPort[i].toString())
            Toast.makeText(this, "Clicked item : ${itemsServerIp[i]}", Toast.LENGTH_SHORT)
                .show()
        }

        listView.setOnItemLongClickListener { _, _, i, _ ->
            val hostname = "${itemsServerIp[i]}:${itemsServerPort[i]}"
            Toast.makeText(this, "Remove: ${itemsServerIp[i]}", Toast.LENGTH_SHORT).show()
            itemsServerIp.removeAt(i)
            itemsServerPort.removeAt(i)
            serverListAdapter.notifyDataSetChanged()

            sharedPreferences.edit {
                itemsIpAndPort.remove(hostname)
                putStringSet("PREF_DESTINATION_IPS", itemsIpAndPort.toSet())
            }
            true
        }

        findViewById<android.view.View>(R.id.addServer).setOnClickListener {
            val currentHost = editHostname.text.toString().trim().ifEmpty { DESTINATION_IP }
            val currentPort = editPort.text.toString().trim().toIntOrNull() ?: DESTINATION_PORT
            val combinedEntry = "$currentHost:$currentPort"

            if (!itemsIpAndPort.contains(combinedEntry)) {
                itemsServerIp.add(currentHost)
                itemsServerPort.add(currentPort)
                itemsIpAndPort.add(combinedEntry)
                serverListAdapter.notifyDataSetChanged()

                sharedPreferences.edit {
                    putStringSet("PREF_DESTINATION_IPS", itemsIpAndPort.toSet())
                    putStringSet(
                        "PREF_DESTINATION_PORTS",
                        itemsServerPort.asSequence().map { it.toString() }.toSet(),
                    )
                }
            }
        }

        val layoutInstructionsHeader = findViewById<android.view.View>(R.id.layoutInstructionsHeader)
        val textExpandCollapse = findViewById<android.widget.TextView>(R.id.textExpandCollapse)
        val textViewGuide = findViewById<android.widget.TextView>(R.id.textView)
        val buttonOpenSheet = findViewById<android.view.View>(R.id.buttonOpenSheet)

        layoutInstructionsHeader?.setOnClickListener {
            if (textViewGuide.visibility == android.view.View.VISIBLE) {
                textViewGuide.visibility = android.view.View.GONE
                textExpandCollapse?.text = "▼"
            } else {
                textViewGuide.visibility = android.view.View.VISIBLE
                textExpandCollapse?.text = "▲"
            }
        }

        buttonOpenSheet?.setOnClickListener {
            showHelpBottomSheet()
        }

        if (!BedrockProxyManager.isProxyRunning()) {
            BedrockProxyManager.start(DESTINATION_IP, DESTINATION_PORT, SOURCE_PORT)
        }

        buttonStartStop.text = if (BedrockProxyManager.isProxyRunning()) "Stop" else "Start"

        val isFirstStart = sharedPreferences.getBoolean("is_first_start", true)
        if (isFirstStart) {
            val initialCombined = "$DESTINATION_IP:$DESTINATION_PORT"
            if (!itemsIpAndPort.contains(initialCombined)) {
                itemsServerIp.add(DESTINATION_IP)
                itemsServerPort.add(DESTINATION_PORT)
                itemsIpAndPort.add(initialCombined)
                serverListAdapter.notifyDataSetChanged()
            }

            sharedPreferences.edit {
                putStringSet("PREF_DESTINATION_IPS", itemsIpAndPort.toSet())
                putStringSet(
                    "PREF_DESTINATION_PORTS",
                    itemsServerPort.asSequence().map { it.toString() }.toSet(),
                )
                putBoolean("is_first_start", false)
            }
        }
    }

    private fun resolveIPAddress(hostname: String): String {
        return try {
            val inetAddress = InetAddress.getByName(hostname)
            inetAddress.hostAddress ?: DESTINATION_IP
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Error resolving hostname: ${e.message}")
            DESTINATION_IP
        }
    }

    private fun saveDestinationSettings(ip: String, port: Int) {
        sharedPreferences.edit {
            putString(PREF_DESTINATION_IP, ip)
            putInt(PREF_DESTINATION_PORT, port)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION || requestCode == REQUEST_INTERNET_PERMISSION) {
            if (BedrockProxyManager.isProxyRunning()) {
                BedrockProxyManager.restart(DESTINATION_IP, DESTINATION_PORT, SOURCE_PORT)
            }
        }
    }

    private fun showHelpBottomSheet() {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        val sheetView = layoutInflater.inflate(R.layout.dialog_help_bottom_sheet, null)
        sheetView.findViewById<android.widget.TextView>(R.id.sheetTextView)?.text = getString(R.string.tutorial)
        sheetView.findViewById<android.view.View>(R.id.buttonCloseSheet)?.setOnClickListener {
            dialog.dismiss()
        }
        dialog.setContentView(sheetView)
        dialog.show()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val name = "Notifications"
            val importance = NotificationManager.IMPORTANCE_LOW
            val mChannel = NotificationChannel(CHANNEL_ID, name, importance)
            notificationManager.createNotificationChannel(mChannel)
        }
    }
}
