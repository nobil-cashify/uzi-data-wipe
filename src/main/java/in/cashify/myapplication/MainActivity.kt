package `in`.cashify.myapplication

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream
import android.util.Log
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Intent
import android.os.Environment
import android.net.Uri
import android.os.StatFs
import android.provider.Settings
import android.widget.TextView
import `in`.cashify.myapplication.utils.DiagnosisStatus
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader


class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_STORAGE_PERMISSION = 100
    private lateinit var tv: TextView;
    private var availableStorage: Long = 0
    private var totalStorage: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkStoragePermissions()

        tv = findViewById<TextView>(R.id.uuid)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                executeWipeScriptInBackground()
            } else {
                Toast.makeText(this, "Please allow 'All files access' in settings.", Toast.LENGTH_LONG).show()
                checkStoragePermissions()
            }
        } else {
            executeWipeScriptInBackground()
        }

    }

    private fun checkStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    REQUEST_CODE_STORAGE_PERMISSION
                )
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                executeWipeScriptInBackground()
            } else {
                Toast.makeText(this, "Storage permission denied.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun executeWipeScriptInBackground() {
        CoroutineScope(Dispatchers.IO).launch {
            val json = getDeviceMetadataJson()
            Log.d("METADATA", json)
            sendMetadata(json)
        }
    }

    fun getDeviceMetadata(): Map<String, Any> {
        val stat = StatFs(Environment.getDataDirectory().path)
        availableStorage = stat.availableBytes / 1073741824
        totalStorage = stat.totalBytes / 1073741824

        val username = try {
            val file = File("/storage/emulated/0/user.json")
            if (file.exists()) {
                val jsonString = file.readText()
                val jsonObject = JSONObject(jsonString)
                jsonObject.getString("username")
            } else {
                "Unknown"
            }
        } catch (e: Exception) {
            "Unknown"
        }

        return mapOf(
            "username" to username.toString(),
            "deviceId" to Build.ID,
            "brand" to Build.BRAND,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "availableStorageInGB" to availableStorage,
            "totalStorageInGB" to totalStorage,
        )
    }

    fun getDeviceMetadataJson(): String {
        val metadata = getDeviceMetadata()
        return JSONObject(metadata).toString()
    }

    fun sendMetadata(json: String) {
        try {
            val scriptFile = copyShellScriptToInternalStorage("send_metadata.sh")
            val command = listOf("/system/bin/sh", scriptFile.absolutePath, json)
            val process = ProcessBuilder(command).redirectErrorStream(true).start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val response = StringBuilder()
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }

            process.waitFor()

            val responseBody = response.toString()
            Log.d("SHELL_API_CALL", "Response: $responseBody")
            val jsonResponse = JSONObject(responseBody)
            val deviceId = jsonResponse.getString("uuid")

//            CoroutineScope(Dispatchers.IO).launch {
//                runPollingConnectionScript(deviceId)
//            }

            runOnUiThread {
                tv.setText("UUID: $deviceId")
            }

            CoroutineScope(Dispatchers.IO).launch {
                pollToRunWipeScript(deviceId)
            }
        } catch (e: Exception) {
            Log.e("API_CALL", "Exception in sendJsonToApi: ${e.message}", e)
        }
    }

    fun runPollingConnectionScript(uuid: String) {
        try {
            val scriptFile = copyShellScriptToInternalStorage("poll_connection_status.sh")
            val command = listOf("/system/bin/sh", scriptFile.absolutePath, uuid)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            // Continuously read and log lines from the shell script
            while (reader.readLine().also { line = it } != null) {
                if (!line.isNullOrBlank()) {
                    Log.d("POLLING_SCRIPT", line!!)
                }
            }

            process.waitFor()

            Log.d("POLLING_SCRIPT", "Polling script exited with code: ${process.exitValue()}")

        } catch (e: Exception) {
            Log.e("POLLING_SCRIPT", "Exception: ${e.message}", e)
        }
    }

    fun pollToRunWipeScript(uuid: String) {
        try {
            val scriptFile = copyShellScriptToInternalStorage("poll_diagnosis_status.sh")

            //registering auxiliary shell files
            copyShellScriptToInternalStorage("wipe_script.sh")
            copyShellScriptToInternalStorage("logger.sh")
            copyShellScriptToInternalStorage("update_progress.sh")

            val command = listOf("/system/bin/sh", scriptFile.absolutePath, uuid)
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            var line: String?

            // Continuously read and log lines from the shell script
            while (reader.readLine().also { line = it } != null) {
                if (!line.isNullOrBlank()) {
                    Log.d("POLLING_SCRIPT", line!!)
                }
            }

            process.waitFor()

            Log.d("POLLING_SCRIPT", "Polling script exited with code: ${process.exitValue()}")

        } catch (e: Exception) {
            Log.e("POLLING_SCRIPT", "Exception: ${e.message}", e)
        }
    }

    fun copyShellScriptToInternalStorage(filename: String): File {
        val inputStream = assets.open(filename)
        val file = File(filesDir, filename)

        inputStream.use { input ->
            FileOutputStream(file).use { output ->
                input.copyTo(output)
            }
        }

        file.setExecutable(true)

        return file
    }
}
