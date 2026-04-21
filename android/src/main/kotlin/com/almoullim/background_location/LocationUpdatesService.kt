package com.almoullim.background_location

import android.annotation.SuppressLint
import android.app.*
import android.location.*
import android.location.LocationListener
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.*
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.common.*

class LocationUpdatesService : Service() {

    private var forceLocationManager: Boolean = false

    override fun onBind(intent: Intent?): IBinder {
        val distanceFilter = intent?.getDoubleExtra("distance_filter", 0.0)
        if (intent != null) {
            forceLocationManager = intent.getBooleanExtra("force_location_manager", false)
        }
        configureLocationProvider()
        if (distanceFilter != null) {
            createLocationRequest(distanceFilter)
        } else {
            createLocationRequest(0.0)
        }
        getLastLocation()
        return mBinder
    }

    private val mBinder = LocalBinder()
    private var mNotificationManager: NotificationManager? = null
    private var mLocationRequest: LocationRequest? = null
    private var mFusedLocationClient: FusedLocationProviderClient? = null
    private var mLocationManager: LocationManager? = null
    private var mFusedLocationCallback: LocationCallback? = null
    private var mLocationManagerCallback: LocationListener? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private var mLocation: Location? = null
    private var notificationBuilder: NotificationCompat.Builder? = null
    private var launchPendingIntent: PendingIntent? = null
    private var notificationIconResId: Int = 0
    private var lastNotificationIcon: String? = null
    private var isGoogleApiAvailable: Boolean = false
    private var isStarted: Boolean = false

    companion object {
        var NOTIFICATION_TITLE = "Background service is running"
        var NOTIFICATION_MESSAGE = "Background service is running"
        var NOTIFICATION_ICON = "@mipmap/ic_launcher"

        private const val PACKAGE_NAME = "com.google.android.gms.location.sample.locationupdatesforegroundservice"
        private val TAG = LocationUpdatesService::class.java.simpleName
        private const val CHANNEL_ID = "channel_01"
        internal const val ACTION_BROADCAST = "$PACKAGE_NAME.broadcast"
        internal const val EXTRA_LOCATION = "$PACKAGE_NAME.location"
        private const val EXTRA_STARTED_FROM_NOTIFICATION = "$PACKAGE_NAME.started_from_notification"
        var UPDATE_INTERVAL_IN_MILLISECONDS: Long = 1000
        var FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = UPDATE_INTERVAL_IN_MILLISECONDS / 2
        private const val NOTIFICATION_ID = 12345678

        private const val STOP_SERVICE = "stop_service"
    }


    @SuppressLint("UnspecifiedImmutableFlag")
    private fun getOrCreateLaunchPendingIntent(): PendingIntent {
        launchPendingIntent?.let { return it }
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        launchIntent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true)
        launchIntent.action = "Localisation"

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 1, launchIntent, flags).also {
            launchPendingIntent = it
        }
    }

    private fun getOrCreateNotificationBuilder(): NotificationCompat.Builder {
        notificationBuilder?.let { return it }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setOngoing(true)
            .setSound(null)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(getOrCreateLaunchPendingIntent())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(CHANNEL_ID)
        }

        notificationBuilder = builder
        return builder
    }

    private fun buildNotification(): Notification {
        if (lastNotificationIcon != NOTIFICATION_ICON || notificationIconResId == 0) {
            notificationIconResId = resources.getIdentifier(NOTIFICATION_ICON, "mipmap", packageName)
            if (notificationIconResId == 0) {
                notificationIconResId = applicationInfo.icon
            }
            lastNotificationIcon = NOTIFICATION_ICON
        }

        return getOrCreateNotificationBuilder()
            .setContentTitle(NOTIFICATION_TITLE)
            .setSmallIcon(notificationIconResId)
            .setWhen(System.currentTimeMillis())
            .setStyle(NotificationCompat.BigTextStyle().bigText(NOTIFICATION_MESSAGE))
            .build()
    }

    override fun onCreate() {
        val googleAPIAvailability = GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(applicationContext)
        
        isGoogleApiAvailable = googleAPIAvailability == ConnectionResult.SUCCESS
        configureLocationProvider()

        mNotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Application Name"
            val mChannel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_DEFAULT)
            mChannel.setSound(null, null)
            mNotificationManager!!.createNotificationChannel(mChannel)
        }

        broadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "stop_service") {
                    removeLocationUpdates()
                }
            }
        }
        val receiver = broadcastReceiver

        val filter = IntentFilter()
        filter.addAction(STOP_SERVICE)
        if (receiver != null) {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
        }


        updateNotification() // to start the foreground service
    }


    fun requestLocationUpdates() {
        Utils.setRequestingLocationUpdates(this, true)
        try {
            val fusedLocationClient = mFusedLocationClient
            val fusedLocationCallback = mFusedLocationCallback
            val locationRequest = mLocationRequest
            val locationManager = mLocationManager
            val locationManagerCallback = mLocationManagerCallback

            if (fusedLocationClient != null && fusedLocationCallback != null && locationRequest != null) {
                fusedLocationClient.requestLocationUpdates(
                    locationRequest,
                    fusedLocationCallback,
                    Looper.myLooper()
                )
            } else if (locationManager != null && locationManagerCallback != null) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    0L,
                    0f,
                    locationManagerCallback
                )
            }
        } catch (unlikely: SecurityException) {
            Utils.setRequestingLocationUpdates(this, false)
        }
    }

    fun updateNotification() {
        val builtNotification = buildNotification()
        if (!isStarted) {
            isStarted = true
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                startForeground(NOTIFICATION_ID, builtNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIFICATION_ID, builtNotification)
            }

        } else {
            mNotificationManager?.notify(NOTIFICATION_ID, builtNotification)
        }
    }

    fun removeLocationUpdates() {
        stopForeground(true)
        stopSelf()
    }


    private fun getLastLocation() {
        try {
            if (mFusedLocationClient != null) {
                mFusedLocationClient?.lastLocation
                        ?.addOnCompleteListener { task ->
                            if (task.isSuccessful && task.result != null) {
                                mLocation = task.result
                            }
                        }
            } else if (mLocationManager != null) {
                mLocation = mLocationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }
        } catch (unlikely: SecurityException) {
        }
    }

    private fun onNewLocation(location: Location) {
        mLocation = location
        val intent = Intent(ACTION_BROADCAST)
        intent.putExtra(EXTRA_LOCATION, location)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)
    }


    private fun createLocationRequest(distanceFilter: Double) {
        val request = mLocationRequest ?: LocationRequest().also {
            mLocationRequest = it
        }
        request.interval = UPDATE_INTERVAL_IN_MILLISECONDS
        request.fastestInterval = FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS
        request.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        request.smallestDisplacement = distanceFilter.toFloat()
    }


    inner class LocalBinder : Binder() {
        internal val service: LocationUpdatesService
            get() = this@LocationUpdatesService
    }


    override fun onDestroy() {
        super.onDestroy()
        isStarted = false
        broadcastReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
            } catch (ignored: IllegalArgumentException) {
                // Receiver may already be unregistered on some shutdown flows.
            }
        }
        broadcastReceiver = null
        removeFusedUpdatesSafely()
        removeLocationManagerUpdatesSafely()

        Utils.setRequestingLocationUpdates(this, false)
        mNotificationManager?.cancel(NOTIFICATION_ID)

        mLocationRequest = null
        mLocation = null
        mFusedLocationClient = null
        mFusedLocationCallback = null
        mLocationManager = null
        mLocationManagerCallback = null
        notificationBuilder = null
        launchPendingIntent = null
        mNotificationManager = null
        notificationIconResId = 0
        lastNotificationIcon = null
    }

    private fun configureLocationProvider() {
        if (isGoogleApiAvailable && !forceLocationManager) {
            removeLocationManagerUpdatesSafely()
            if (mFusedLocationClient == null) {
                mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            }
            if (mFusedLocationCallback == null) {
                mFusedLocationCallback = object : LocationCallback() {
                    override fun onLocationResult(locationResult: LocationResult) {
                        val newLastLocation = locationResult.lastLocation
                        if (newLastLocation is Location) {
                            super.onLocationResult(locationResult)
                            onNewLocation(newLastLocation)
                        }
                    }
                }
            }
            mLocationManager = null
            mLocationManagerCallback = null
        } else {
            removeFusedUpdatesSafely()
            if (mLocationManager == null) {
                mLocationManager = getSystemService(LOCATION_SERVICE) as LocationManager?
            }
            if (mLocationManagerCallback == null) {
                mLocationManagerCallback = LocationListener { location ->
                    onNewLocation(location)
                }
            }
            mFusedLocationClient = null
            mFusedLocationCallback = null
        }
    }

    private fun removeFusedUpdatesSafely() {
        try {
            mFusedLocationClient?.let { client ->
                mFusedLocationCallback?.let { callback ->
                    client.removeLocationUpdates(callback)
                }
            }
        } catch (ignored: SecurityException) {
        }
    }

    private fun removeLocationManagerUpdatesSafely() {
        try {
            mLocationManager?.let { locationManager ->
                mLocationManagerCallback?.let { callback ->
                    locationManager.removeUpdates(callback)
                }
            }
        } catch (ignored: SecurityException) {
        }
    }
}
