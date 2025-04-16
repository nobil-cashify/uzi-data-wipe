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
import java.io.IOException


class MainActivity : AppCompatActivity() {
    private val REQUEST_CODE_STORAGE_PERMISSION = 100
    private lateinit var outputTV: TextView
    private lateinit var button: Button
    private lateinit var healthCheckButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkStoragePermissions()

        button = findViewById<Button>(R.id.sanitizeButton)
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

        healthCheckButton = findViewById<Button>(R.id.healthButton)
        healthCheckButton.setOnClickListener {
            runHealthCheck()
        }

        outputTV = findViewById<TextView>(R.id.outputTextView)

    }

    private fun runHealthCheck() {
        CoroutineScope(Dispatchers.IO).launch {
            val scriptFile = File(filesDir, "health_check.sh")

            try {
                // Copy script from assets to internal storage
                assets.open("health_check.sh").use { input ->
                    FileOutputStream(scriptFile).use { output ->
                        input.copyTo(output)
                    }
                }

                scriptFile.setExecutable(true)

                // Run the script and capture output
                val process = Runtime.getRuntime().exec(arrayOf("sh", scriptFile.absolutePath))

                val output = process.inputStream.bufferedReader().readText().trim()
                val error = process.errorStream.bufferedReader().readText().trim()
                process.waitFor()
                Log.d("TAG", "runHealthCheck: $error")
                withContext(Dispatchers.Main) {
                    if (error.isNotEmpty()) {
                        Toast.makeText(this@MainActivity, "Error: $error", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@MainActivity, output, Toast.LENGTH_LONG).show()
                        outputTV.text = output
                    }
                }

            } catch (e: Exception) {
                Log.e("HealthCheck", "Script failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Exception: ${e.message}", Toast.LENGTH_LONG).show()
                }
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
//            val file = saveJsonToFile(json, "device_metadata.json")
            sendMetadata(json)

            //poll to run wiping shell
//            pollToRunScript()
//            runWipeScript()
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

            val process = Runtime.getRuntime().exec(arrayOf("sh", scriptFile.absolutePath, deviceDiagnosisId))

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
        val availableBytes = stat.availableBytes
        val totalBytes = stat.totalBytes
        return mapOf(
            "brand" to Build.BRAND,
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "device" to Build.DEVICE,
            "product" to Build.PRODUCT,
            "androidVersion" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT,
            "availableStorage" to availableBytes,
            "totalStorage" to totalBytes,
        )
    }

    fun getDeviceMetadataJson(): String {
        val metadata = getDeviceMetadata()
        return JSONObject(metadata).toString()
    }

    fun saveJsonToFile(json: String, fileName: String): File {
        val file = File(filesDir, fileName)
        file.writeText(json)
        return file
    }

    fun sendMetadata(json: String) {
        try {
            val client = OkHttpClient()

            val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), json)
            val request = Request.Builder()
                .url("http://192.168.1.162:8080/data-wipe/v1/device/register")
//                .header("x-sso-token", "eyJhbGciOiJIUzI1NiJ9.eyJjVGlkIjoiMzFlN2I0MzctODcyNC00MzQ2LThiZGUtOGYzOGE5NWI0MDg0IiwiZXhwIjoxNzQ0NjU1Mzk5LCJndCI6ImNvbnNvbGUiLCJ2dCI6MCwia2lkIjoiNDY1NCJ9.V9c6g2IbNyGQ2JDX5W-i2jPEUi3V4Lf7QHOl5QX2KAE")
                .post(requestBody)
                .build()

            Log.d("API_CALL", "About to enqueue request")

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("API_CALL", "Failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.e("API_CALL", "Unexpected code $response")
                            return
                        }

                        val responseBody = response.body?.string()
                        Log.d("API_CALL", "Response: $responseBody")

                        try {
                            val jsonResponse = JSONObject(responseBody ?: "")
                            val deviceId =
                                jsonResponse.getInt("id")
                            Log.d("API_CALL", "Device ID: $deviceId")

                            val prefs = getSharedPreferences("MyPrefs", MODE_PRIVATE)
                            prefs.edit().putInt("diagnosis_id", deviceId).apply()

                            runOnUiThread {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Registered with ID: $deviceId",
                                    Toast.LENGTH_LONG
                                ).show()
                            }

                            val registerDiagnosisJson = JSONObject(mapOf("deviceId" to deviceId)).toString();
                            registerDiagnosis(registerDiagnosisJson);

                        } catch (e: Exception) {
                            Log.e("API_CALL", "Error parsing response: ${e.message}", e)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("API_CALL", "Exception in sendJsonToApi: ${e.message}", e)
        }
    }

    fun registerDiagnosis(registerJson: String) {
        try {
            val client = OkHttpClient()
            val requestBody = RequestBody.create("application/json; charset=utf-8".toMediaTypeOrNull(), registerJson)
            val request = Request.Builder()
                .url("http://192.168.1.162:8080/data-wipe/v1/diagnosis/register")
//                .header("x-sso-token", "eyJhbGciOiJIUzI1NiJ9.eyJjVGlkIjoiMzFlN2I0MzctODcyNC00MzQ2LThiZGUtOGYzOGE5NWI0MDg0IiwiZXhwIjoxNzQ0NjU1Mzk5LCJndCI6ImNvbnNvbGUiLCJ2dCI6MCwia2lkIjoiNDY1NCJ9.V9c6g2IbNyGQ2JDX5W-i2jPEUi3V4Lf7QHOl5QX2KAE")
                .post(requestBody)
                .build()

            Log.d("API_CALL", "About to enqueue request")
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("API_CALL", "Failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!response.isSuccessful) {
                            Log.e("API_CALL", "Unexpected code $response")
                            return
                        }
                        val responseBody = response.body?.string()
                        Log.d("API_CALL", "Response: $responseBody")

                        try {
                            val jsonResponse = JSONObject(responseBody ?: "")
                            val deviceDiagnosisId =
                                jsonResponse.getInt("id")
                            Log.d("API_CALL", "Diagnosis ID: $deviceDiagnosisId")

                            val prefs = getSharedPreferences("MyPrefs", MODE_PRIVATE)
                            prefs.edit().putInt("diagnosis_id", deviceDiagnosisId).apply()

                            CoroutineScope(Dispatchers.IO).launch {
                                pollToRunScript(deviceDiagnosisId)
                            }


                        } catch (e: Exception) {
                            Log.e("API_CALL", "Error parsing response: ${e.message}", e)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            Log.e("API_CALL", "Exception in registerDiagnosis: ${e.message}", e)
        }
    }

    suspend fun pollToRunScript(deviceDiagnosisId: Int) {
        val client = OkHttpClient()
        var status = DiagnosisStatus.UNINITIATED

        while (status != DiagnosisStatus.INITIATED) {
            try {
                val request = Request.Builder()
                    .url("http://192.168.1.162:8080/data-wipe/v1/diagnosis/status/$deviceDiagnosisId")
//                    .header("x-sso-token", "eyJhbGciOiJIUzI1NiJ9.eyJjVGlkIjoiMzFlN2I0MzctODcyNC00MzQ2LThiZGUtOGYzOGE5NWI0MDg0IiwiZXhwIjoxNzQ0NjU1Mzk5LCJndCI6ImNvbnNvbGUiLCJ2dCI6MCwia2lkIjoiNDY1NCJ9.V9c6g2IbNyGQ2JDX5W-i2jPEUi3V4Lf7QHOl5QX2KAE")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e("API_CALL", "Polling failed: ${response.code}")
                } else {
                    val responseBody = response.body?.string()
                    val jsonResponse = JSONObject(responseBody ?: "")
                    val statusString = jsonResponse.getString("status").uppercase()
                    Log.d("POLLING", "Received status: $statusString")
                    status = DiagnosisStatus.valueOf(statusString)

                    if (status == DiagnosisStatus.INITIATED) {
                        Log.d("POLLING", "Initiated. Running script...")
                        withContext(Dispatchers.IO) {
                            Log.d("API_CALL", "pollToRunScript: RUNNING WIPE SCRIPT")
                            runWipeScript(deviceDiagnosisId.toString())
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





}
