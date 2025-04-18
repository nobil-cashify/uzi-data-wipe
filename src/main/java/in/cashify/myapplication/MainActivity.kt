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
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader


class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_STORAGE_PERMISSION = 100
    private lateinit var button: Button

    private var availableStorage: Long = 0
    private var totalStorage: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        button = findViewById<Button>(R.id.sanitizeButton)

        checkStoragePermissions()

        button.setOnClickListener {
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

            CoroutineScope(Dispatchers.IO).launch {
                pollToRunWipeScript(deviceId)
            }
        } catch (e: Exception) {
            Log.e("API_CALL", "Exception in sendJsonToApi: ${e.message}", e)
        }
    }

    private fun runWipeScript(deviceDiagnosisId: String) {
        val scriptFile = File(filesDir, "wipe_script.sh")

        try {
            assets.open("wipe_script.sh").use { input ->
                FileOutputStream(scriptFile).use { output ->
                    input.copyTo(output)
                }
            }

            scriptFile.setExecutable(true)

            val process = Runtime.getRuntime()
                .exec(arrayOf("sh",
                    scriptFile.absolutePath,
                    deviceDiagnosisId))

            process.inputStream.bufferedReader().forEachLine {
                Log.d("ScriptOutput", it)
            }

            process.errorStream.bufferedReader().forEachLine {
                Log.e("ScriptError", it)
            }

            process.waitFor()
        } catch (e: Exception) {
            Log.e("ScriptRun", "Failed to execute script", e)
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

    fun runPollingConnectionScript(uuid: String) {
        try {
            val scriptFile = copyShellScriptToInternalStorage("poll_status.sh")
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

    suspend fun pollToRunWipeScript(deviceId: String) {
        val client = OkHttpClient()
        var status = DiagnosisStatus.UNINITIATED

        while (status != DiagnosisStatus.INITIATED) {
            try {
                val request = Request.Builder()
                    .url("http://192.168.1.176:8080/data-wipe/v1/device/diagnosis/$deviceId")
                    .header("x-sso-token", "eyJhbGciOiJIUzI1NiJ9.eyJjVGlkIjoiOTZlZDdjMjMtZjc2My00MTg4LWExZjMtYjYwNmNlY2E5ZmY5IiwiZXhwIjoxNzQ1MDAwOTk5LCJndCI6ImNvbnNvbGUiLCJ2dCI6MCwia2lkIjoiNDY1OCJ9.olaI3HjQ94q3kj60IuAMVM439Era5cg2PJmuDV2hZf8")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("API_CALL", "Polling failed: ${response.code}")
                } else {
                    val responseBody = response.body?.string()
                    val jsonResponse = JSONObject(responseBody ?: "")
                    val statusString = jsonResponse.getString("status")
                    Log.d("POLLING", "Received status: $statusString")
                    status = DiagnosisStatus.valueOf(statusString)

                    if (status == DiagnosisStatus.INITIATED) {
                        Log.d("POLLING", "Initiated. Running script...")
                        withContext(Dispatchers.IO) {
                            Log.d("API_CALL", "pollToRunScript: RUNNING WIPE SCRIPT")
                            runWipeScript(deviceId)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("POLLING", "Error during polling: ${e.message}")
            }

            delay(5000)
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
