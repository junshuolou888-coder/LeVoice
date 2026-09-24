package com.stv.voice

import android.app.*
import android.content.*
import android.os.*
import android.util.Log
import com.localvoicetv.*

internal interface VoiceEngine {
    fun initialize()
    fun start()
    fun stop()
    fun release()
}

/** OEM hardware entry point. Owns one recognizer; no Activity is required to listen. */
class WindowService : Service(), SherpaSpeechRecognizer.Listener {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: VoiceEngine
    private lateinit var feedback: VoiceFeedback
    private lateinit var registry: CommandRegistry
    private lateinit var commands: CommandExecutor
    private val requests = LatestRequestRunner(deliver = { callback -> main.post { callback() } })
    private var ready = false
    private var pressed = false
    private var recording = false
    private var completed = false
    private var cancelled = false
    private var destroyed = false
    private var modelFailed = false
    private var weatherClient: QWeatherClient? = null
    private val timeout = Runnable { releasePress() }
    private val idle = Runnable { stopSelf() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            cancelled = true
            pressed = false
            engine.stop()
            requests.cancel()
            feedback.hide()
            main.removeCallbacks(timeout)
            scheduleIdle()
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "遥控器语音", NotificationManager.IMPORTANCE_LOW))
        }
        val notification = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        startForeground(731, notification.setSmallIcon(R.drawable.ic_voice)
            .setContentTitle("本地语音助手").setContentText("按住遥控器语音键说话")
            .setOngoing(true).build())
        feedback = feedbackFactory(this)
        val config = CommandConfigLoader.load(applicationContext)
        registry = CommandRegistry(config)
        commands = CommandExecutor(this, mapOf("check_weather" to ::queryWeather))
        engine = engineFactory(this, this)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        engine.initialize()
        scheduleIdle()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            PREPARE -> { feedback.show(if (ready) "语音已就绪，按住语音键说话" else "语音助手正在准备，请稍等…", 4000); scheduleIdle() }
            OPEN_MIC -> if (!pressed) {
                pressed = true
                Log.i(TAG, "Hardware voice DOWN")
                main.removeCallbacks(idle)
                main.removeCallbacks(timeout)
                main.postDelayed(timeout, MAX_PRESS_MS)
                when {
                    modelFailed -> feedback.show("语音暂不可用，请稍后再试", 4000)
                    !ready -> feedback.show("语音助手正在准备，请稍后再按语音键…")
                    recording -> feedback.show("正在处理上一句话…")
                    else -> {
                        requests.cancel()
                        cancelled = false
                        completed = false
                        recording = true
                        feedback.show("正在听…\n松开语音键结束")
                        engine.start()
                    }
                }
            }
            CLOSE_MIC -> { Log.i(TAG, "Hardware voice UP"); releasePress() }
            else -> if (!recording) scheduleIdle()
        }
        return START_NOT_STICKY
    }

    private fun releasePress() {
        if (!pressed) return
        pressed = false
        main.removeCallbacks(timeout)
        if (recording) {
            if (!completed) feedback.show("正在处理您刚才说的话，请稍等…")
            engine.stop()
        } else if (!ready && !modelFailed) feedback.show("语音助手正在准备，就绪后请再按语音键", 4000)
        scheduleIdle()
    }
    private fun scheduleIdle() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }
    private fun deliver(block: () -> Unit) { main.post { if (!destroyed) block() } }

    override fun onModelReady() = deliver {
        ready = true
        // Never record late: loading may outlive the physical key press.
        if (!cancelled) feedback.show("语音已就绪，请按住语音键说话", 3000)
        scheduleIdle()
    }
    override fun onListeningChanged(isListening: Boolean) = deliver {
        if (!isListening) {
            recording = false
            if (!completed && !cancelled) feedback.show("没有听清，请再试一次", 4000)
            scheduleIdle()
        }
    }
    override fun onPartialResult(text: String) = deliver {
        if (!cancelled) {
            Log.d("VoiceRecognition", "partial=$text")
            feedback.show(if (pressed) "正在听…\n$text" else "正在处理…\n$text")
        }
    }
    override fun onFinalResult(text: String) = deliver {
        if (!cancelled && !completed) {
            completed = true
            Log.d("VoiceRecognition", "final=$text")
            val match = registry.match(text)
            if (match == null) feedback.show("抱歉，还没明白您的意思，请换个说法", 5000)
            else try {
                if (commands.execute(match.entry.action, match.variables) == CommandExecution.COMPLETED)
                    feedback.show(CommandFeedback.success(match), 3500)
            } catch (error: Exception) { feedback.show(error.message ?: "执行失败，请重试", 5000) }
        }
    }
    override fun onError(error: Throwable) = deliver {
        Log.e(TAG, "Voice error", error)
        completed = true
        if (!ready) modelFailed = true
        if (!cancelled) feedback.show(error.message ?: "语音暂不可用，请重试", 6000)
        if (modelFailed) { main.removeCallbacks(idle); main.postDelayed(idle, 6000) }
    }

    private fun queryWeather(params: Map<String, String>): CommandExecution {
        require(params.keys.all { it in setOf("city", "period", "defaultPeriod") }) { "天气参数不支持" }
        val period = WeatherPeriod.parse(params["period"].orEmpty().ifEmpty { params["defaultPeriod"].orEmpty() })
        val client = weatherClient ?: QWeatherClient(WeatherSettingsStore(this).load().also { it.validate() }).also { weatherClient = it }
        val started = SystemClock.elapsedRealtime()
        Log.i("WeatherQuery", "start period=${period.name} explicitCity=${!params["city"].isNullOrBlank()}")
        feedback.show("正在为您查询${params["city"].orEmpty()}${period.label}天气…", 15000)
        requests.submit({ cancellation -> client.query(params["city"].orEmpty(), period, cancellation) }) { result ->
            if (!destroyed && !cancelled) {
                Log.i("WeatherQuery", "complete elapsedMs=${SystemClock.elapsedRealtime() - started} success=${result.isSuccess} error=${result.exceptionOrNull()?.javaClass?.simpleName.orEmpty()}")
                feedback.show(result.fold({ it.displayText() }, {
                    if (it is AmbiguousCityException) "地区名称不明确，请补充省市后再查询"
                    else it.message ?: "天气查询失败，请稍后重试"
                }), 12000)
                scheduleIdle()
            }
        }
        return CommandExecution.PENDING
    }

    override fun onDestroy() {
        if (destroyed) return
        destroyed = true
        main.removeCallbacksAndMessages(null)
        unregisterReceiver(screenOff)
        requests.close()
        engine.release()
        feedback.hide()
        stopForeground(true)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val OPEN_MIC = "com.letv.openmic.with.hardware"
        const val CLOSE_MIC = "com.letv.closemic.with.hardware"
        const val PREPARE = "com.localvoicetv.PREPARE_VOICE"
        const val MAX_PRESS_MS = 30000L
        const val IDLE_MS = 300000L
        private const val CHANNEL = "hardware_voice"
        private const val TAG = "StvVoiceBridge"
        internal var feedbackFactory: (Context) -> VoiceFeedback = { VoiceOverlay(it) }
        internal var engineFactory: (Context, SherpaSpeechRecognizer.Listener) -> VoiceEngine = { context, listener ->
            object : VoiceEngine {
                private val recognizer = SherpaSpeechRecognizer(context.applicationContext, listener)
                override fun initialize() { recognizer.initialize("", 8f) }
                override fun start() { recognizer.startListening() }
                override fun stop() { recognizer.stopListening() }
                override fun release() { recognizer.release() }
            }
        }
    }
}
