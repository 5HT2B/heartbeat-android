package com.technicallyfunctional.digitalheartbeat

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import androidx.preference.PreferenceManager
import com.technicallyfunctional.digitalheartbeat.StatusFragment.Companion.ACTION_UPDATE
import java.io.FileDescriptor
import java.time.Instant

class ForegroundService: Service() {
    class TRANSACTION
    {
        companion object {
            const val GET_STATUS: Int = 1
            const val GET_LAST_PING: Int = 2
            const val GET_LAST_LOCATION: Int = 3
            const val GET_LAST_BATTERY: Int = 4
            const val GET_LAST_ERROR: Int = 5
        }
    }

    var running: Boolean = false
    var since: Instant? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Instant.now()
    } else {
        null
    }

    private val bgService = BackgroundService()

    var lastError: String = ""

    private var context: Context? = null

    private var notificationChannel: NotificationChannel? = null
    private var notification: Notification? = null
    private var notificationManager: NotificationManager? = null

    private var status: String = ""
    private var subStatus: String = ""
    private var details: String = ""

    private var defaultSharedPreferences: SharedPreferences? = null

    override fun onBind(intent: Intent?): IBinder {
        return Binder()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        context = baseContext
        defaultSharedPreferences = PreferenceManager.getDefaultSharedPreferences(context!!)
        if (notificationChannel == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            createNotificationChannel()
        if (notification == null)
            createNotification()
        startForeground(1, notification)

        context?.registerReceiver(bgService, IntentFilter(Intent.ACTION_TIME_TICK))
        context?.registerReceiver(bgService, IntentFilter(Intent.ACTION_SCREEN_ON))
        context?.registerReceiver(bgService, IntentFilter(Intent.ACTION_SCREEN_OFF))
        context?.registerReceiver(bgService, IntentFilter(Intent.ACTION_USER_PRESENT))
        running = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            since = Instant.now()
            bgService.since = since
        }
        bgService.notification = notification
        val notificationUpdateReceiver: NotificationUpdateReceiver = NotificationUpdateReceiver()
        notificationUpdateReceiver.foregroundService = this
        context?.registerReceiver(notificationUpdateReceiver, IntentFilter(ACTION_UPDATE))
        return START_STICKY
    }

    class NotificationUpdateReceiver: BroadcastReceiver() {
        var foregroundService: ForegroundService? = null

        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent != null) {
                if (intent.action == ACTION_UPDATE) {
                    if (intent.extras != null) {
                        foregroundService?.status = intent.extras!!.getString("status").toString()
                        foregroundService?.subStatus = intent.extras!!.getString("substatus").toString()
                        foregroundService?.details = intent.extras!!.getString("statusdetails").toString()

                        foregroundService?.createNotification()

                        foregroundService?.notificationManager?.notify(1, foregroundService?.notification)
                    }
                }
            }
        }

    }

    @SuppressLint("NewApi")
    private fun createNotificationChannel() {
        val name = getString(R.string.fgservice_notification_channel_name)
        val descriptionText = getString(R.string.fgservice_notification_channel_name)
        val importance = NotificationManager.IMPORTANCE_DEFAULT
        notificationChannel = NotificationChannel("fgservice", name, importance)
        notificationChannel!!.description = descriptionText
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager!!.createNotificationChannel(notificationChannel!!)
    }

    private fun createNotification() {
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(defaultSharedPreferences?.getString("server_hostname", "https://hb.l1v.in/"))
        val notificationStatus: String = if(status != "")
            status
        else
            getText(R.string.fgservice_notification_title) as String
        val notificationSubStatus: String = if(subStatus != "")
            "$subStatus\n$details"
        else
            getText(R.string.fgservice_notification_content_text) as String
        val pendingIntent = TaskStackBuilder.create(context).run {
            addNextIntentWithParentStack(intent)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT + PendingIntent.FLAG_IMMUTABLE)
            } else {
                getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notification = Notification.Builder(context, "fgservice")
                .setContentTitle(notificationStatus)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setStyle(Notification.BigTextStyle().bigText(notificationSubStatus))
                .build()
        } else {
            @Suppress("DEPRECATION")
            notification = Notification.Builder(context)
                .setContentTitle(notificationStatus)
                .setContentText(notificationSubStatus)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setStyle(Notification.BigTextStyle().bigText(notificationSubStatus))
                .build()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        context?.unregisterReceiver(bgService)
        stopSelf()
    }

    inner class Binder : IBinder {

        override fun getInterfaceDescriptor(): String {
            return "HeartbeatService"
        }

        override fun pingBinder(): Boolean {
            return this@ForegroundService.running
        }

        override fun isBinderAlive(): Boolean {
            return this@ForegroundService.running
        }

        override fun queryLocalInterface(descriptor: String): IInterface? {
            return null
        }

        override fun dump(fileDescriptor: FileDescriptor, args: Array<out String>?) {
            throw NotImplementedError()
        }

        override fun dumpAsync(fileDescriptor: FileDescriptor, args: Array<out String>?) {
            throw NotImplementedError()
        }

        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            when (code)
            {
                TRANSACTION.GET_STATUS -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (running)
                            reply?.writeString("Running\nsince ${since.toString()}")
                        else
                            reply?.writeString("Stopped\nsince ${since.toString()}")
                    }
                    else {
                        if (running)
                            reply?.writeString("Running")
                        else
                            reply?.writeString("Stopped")
                    }
                    reply?.setDataPosition(0)
                    return true
                }

                TRANSACTION.GET_LAST_PING -> {
                    //if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        //if (lastPing != null) {
                        //    val formatter: DateTimeFormatter =
                        //        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                        //    reply?.writeString(formatter.format(lastPing))
                        //}
                        reply?.writeInt(bgService.lastUpdateTimestamp)
                    //} else {
                    //    reply?.writeString("Unknown (API Version too low)")
                    //}
                    reply?.setDataPosition(0)
                    return true
                }

                TRANSACTION.GET_LAST_BATTERY -> {
                    reply?.writeString(bgService.lastBattery)
                    reply?.setDataPosition(0)
                    return true

                }

                TRANSACTION.GET_LAST_LOCATION -> {
                    if (bgService.lastLocation != null)
                        reply?.writeString(bgService.lastLocation.toString())
                    else
                        reply?.writeString("Unknown!")
                    reply?.setDataPosition(0)
                    return true
                }

                TRANSACTION.GET_LAST_ERROR -> {
                    return if(lastError != "") {
                        reply?.writeString(lastError)
                        reply?.setDataPosition(0)
                        true
                    } else false
                }

                else ->  return false
            }
        }

        override fun linkToDeath(deathRecipient: IBinder.DeathRecipient, flags: Int) {
            TODO("Not yet implemented")
        }

        override fun unlinkToDeath(deathRecipient: IBinder.DeathRecipient, flags: Int): Boolean {
            TODO("Not yet implemented")
        }
    }
}