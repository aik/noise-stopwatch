package com.example.handsfreestopwatch

import android.Manifest
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.example.handsfreestopwatch.databinding.ActivityMainBinding
import kotlin.math.log10
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())

    @Volatile private var threshold = 40f
    @Volatile private var alarmMillis = 9000L       // срабатывание по суммарному времени шума (активный таймер)
    @Volatile private var alarmFired = false
    @Volatile private var running = false
    private var audioThread: Thread? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var durFields: List<EditText>
    private lateinit var durRadios: List<RadioButton>
    private var activeTimer = 0
    private val durDefaults = listOf(9.5, 13.0, 35.0)

    // Отложенное автопереключение на 3-й таймер (с обратным отсчётом).
    private val autoSwitchDelaySec = 5
    private var autoSwitchActive = false
    private var autoSwitchLeft = 0
    private val autoSwitchRunnable = object : Runnable {
        override fun run() {
            if (!autoSwitchActive) return
            if (autoSwitchLeft <= 0) {
                autoSwitchActive = false
                selectTimer(2)
                return
            }
            binding.textStatus.text = getString(R.string.switching_in, autoSwitchLeft)
            autoSwitchLeft--
            ui.postDelayed(this, 1000)
        }
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    // Таймер шума
    @Volatile private var totalMillis = 0L          // накопленная сумма всех интервалов
    @Volatile private var intervalStart = 0L        // старт текущего интервала
    @Volatile private var lastLoudAt = 0L           // когда последний раз был шум выше порога
    @Volatile private var inNoise = false

    private val quietGraceMs = 400L                 // гистерезис: сколько терпеть тишину, не закрывая интервал

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMonitoring()
            else Toast.makeText(this, R.string.perm_needed, Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        durFields = listOf(binding.edit1, binding.edit2, binding.edit3)
        durRadios = listOf(binding.radio1, binding.radio2, binding.radio3, binding.radioNoLimit)

        loadSettings()

        binding.thresholdSlider.addOnChangeListener { _, value, _ ->
            threshold = value
            binding.textThreshold.text = value.toInt().toString()
            prefs.edit().putFloat("threshold", value).apply()
        }

        durFields.forEachIndexed { i, field ->
            field.addTextChangedListener {
                val v = fieldValue(i)
                prefs.edit().putFloat("sec$i", v.toFloat()).apply()
                if (i == activeTimer) alarmMillis = (v * 1000).toLong()
                if (i == activeTimer && !running) updateTexts(level = 0, noise = false)
            }
        }

        durRadios.forEachIndexed { i, radio ->
            radio.setOnClickListener { selectTimer(i) }
        }

        // Клик по всей ячейке (не только по кружку) выбирает таймер — так труднее промахнуться.
        listOf(binding.timerCell1, binding.timerCell2, binding.timerCell3, binding.timerCellNoLimit)
            .forEachIndexed { i, cell -> cell.setOnClickListener { selectTimer(i) } }

        binding.checkAutostart.setOnCheckedChangeListener { _, checked ->
            cancelAutoSwitch()
            prefs.edit().putBoolean("autostart", checked).apply()
        }

        binding.checkAutoThird.setOnCheckedChangeListener { _, checked ->
            cancelAutoSwitch()
            prefs.edit().putBoolean("autothird", checked).apply()
        }

        binding.btnStartStop.setOnClickListener {
            cancelAutoSwitch()
            if (running) stopMonitoring() else ensurePermissionAndStart()
        }

        binding.btnReset.setOnClickListener {
            cancelAutoSwitch()
            resetTimer()
        }

        updateTexts(level = 0, noise = false)
    }

    private fun ensurePermissionAndStart() {
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) startMonitoring() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startMonitoring() {
        if (running) return
        running = true
        inNoise = false
        alarmFired = false
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnStartStop.setText(R.string.stop)

        audioThread = Thread { audioLoop() }.also { it.start() }
    }

    private fun stopMonitoring() {
        running = false
        audioThread?.join(1000)
        audioThread = null
        finalizeIntervalIfOpen()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.btnStartStop.setText(R.string.start)
        ui.post { updateTexts(level = 0, noise = false) }
    }

    private fun resetTimer() {
        totalMillis = 0L
        inNoise = false
        alarmFired = false
        intervalStart = 0L
        ui.post { updateTexts(level = 0, noise = inNoise) }
    }

    private fun audioLoop() {
        val sampleRate = 44100
        val channel = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channel, encoding)
        val frameSize = sampleRate / 10 // ~100 мс
        val bufSize = maxOf(minBuf, frameSize * 2)

        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, channel, encoding, bufSize)
        } catch (e: SecurityException) {
            running = false
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            running = false
            return
        }

        val buffer = ShortArray(frameSize)
        record.startRecording()
        try {
            while (running) {
                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue

                val level = computeLevel(buffer, n)
                val now = System.currentTimeMillis()
                val loud = level >= threshold

                if (loud) {
                    if (!inNoise) {
                        inNoise = true
                        intervalStart = now
                    }
                    lastLoudAt = now
                    if (hasLimit() && !alarmFired &&
                        totalMillis + (now - intervalStart) >= alarmMillis
                    ) {
                        alarmFired = true
                        vibrate()
                        ui.post {
                            flashIndicator()
                            if (binding.checkAutoThird.isChecked) startAutoSwitchCountdown()
                        }
                        // Таймер НЕ останавливаем — продолжаем считать до нажатия другой радиокнопки.
                    }
                } else if (inNoise && now - lastLoudAt >= quietGraceMs) {
                    // Шум закончился: засчитываем интервал до последнего громкого момента.
                    totalMillis += lastLoudAt - intervalStart
                    inNoise = false
                }

                ui.post { updateTexts(level, inNoise) }
            }
        } finally {
            try { record.stop() } catch (_: IllegalStateException) {}
            record.release()
        }
    }

    private fun loadSettings() {
        val th = prefs.getFloat("threshold", 40f)
        binding.thresholdSlider.value = th
        threshold = th
        binding.textThreshold.text = th.toInt().toString()

        durFields.forEachIndexed { i, field ->
            field.setText(fmtSec(prefs.getFloat("sec$i", durDefaults[i].toFloat()).toDouble()))
        }

        activeTimer = prefs.getInt("active", 0).coerceIn(0, durRadios.size - 1)
        durRadios.forEachIndexed { i, r -> r.isChecked = (i == activeTimer) }
        if (hasLimit()) alarmMillis = (fieldValue(activeTimer) * 1000).toLong()

        binding.checkAutostart.isChecked = prefs.getBoolean("autostart", false)
        binding.checkAutoThird.isChecked = prefs.getBoolean("autothird", false)
    }

    private fun selectTimer(index: Int) {
        cancelAutoSwitch()
        activeTimer = index
        durRadios.forEachIndexed { i, r -> r.isChecked = (i == index) }
        if (hasLimit()) alarmMillis = (fieldValue(index) * 1000).toLong()
        prefs.edit().putInt("active", index).apply()
        // Нажатие радиокнопки начинает отсчёт заново.
        totalMillis = 0L
        alarmFired = false
        intervalStart = System.currentTimeMillis()
        if (binding.checkAutostart.isChecked && !running) ensurePermissionAndStart()
        if (!running) updateTexts(level = 0, noise = false)
    }

    private fun startAutoSwitchCountdown() {
        autoSwitchActive = true
        autoSwitchLeft = autoSwitchDelaySec
        ui.removeCallbacks(autoSwitchRunnable)
        ui.post(autoSwitchRunnable)
    }

    /** Отмена отложенного автопереключения (по нажатию любой кнопки). */
    private fun cancelAutoSwitch() {
        if (!autoSwitchActive) return
        autoSwitchActive = false
        ui.removeCallbacks(autoSwitchRunnable)
        if (!running) updateTexts(level = 0, noise = false)
    }

    /** true, если выбран один из таймеров с лимитом (не «Без лимита»). */
    private fun hasLimit(): Boolean = activeTimer < durFields.size

    /** Значение поля в секундах (0.1..86400), допускает доли секунды; при пустом/битом вводе — дефолт. */
    private fun fieldValue(i: Int): Double =
        durFields[i].text.toString().replace(',', '.').toDoubleOrNull()
            ?.coerceIn(0.1, 86_400.0) ?: durDefaults[i]

    /** Целое показываем без ".0", дробное — как есть ("9.5"). */
    private fun fmtSec(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    /** Подсветить статус-блок на 1 секунду как визуальный сигнал. */
    private fun flashIndicator() {
        binding.textStatus.setText(R.string.status_timeup)
        binding.textStatus.setBackgroundColor(0xFFFFC107.toInt())
        ui.postDelayed({ binding.textStatus.setBackgroundColor(0x00000000) }, 1000)
    }

    private fun vibrate() {
        if (!vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 400, 200, 400, 200, 400)
            val amplitudes = intArrayOf(0, 255, 0, 255, 0, 255)
            vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(longArrayOf(0, 400, 200, 400, 200, 400), -1)
        }
    }

    private fun finalizeIntervalIfOpen() {
        if (inNoise) {
            totalMillis += lastLoudAt - intervalStart
            inNoise = false
        }
    }

    /** RMS -> dBFS -> шкала 0..100 (−60 dBFS = 0, 0 dBFS = 100). */
    private fun computeLevel(buf: ShortArray, n: Int): Int {
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = buf[i].toDouble()
            sumSq += s * s
        }
        val rms = sqrt(sumSq / n)
        if (rms < 1.0) return 0
        val db = 20.0 * log10(rms / 32767.0) // от -90 до 0
        val level = ((db + 60.0) / 60.0 * 100.0)
        return level.coerceIn(0.0, 100.0).toInt()
    }

    private fun updateTexts(level: Int, noise: Boolean) {
        binding.levelBar.progress = level

        val liveMillis = if (noise) System.currentTimeMillis() - intervalStart else 0L
        val targetStr = if (hasLimit()) fmt(alarmMillis) else "∞"
        binding.textTotal.text = "${fmt(totalMillis + liveMillis)} / $targetStr"
        binding.textCurrent.text = fmt(liveMillis)

        if (!autoSwitchActive) {
            binding.textStatus.setText(
                when {
                    !running -> R.string.status_idle
                    noise -> R.string.status_loud
                    else -> R.string.status_quiet
                }
            )
        }
    }

    private fun fmt(ms: Long): String {
        val tenths = (ms / 100) % 10
        val totalSec = ms / 1000
        val sec = totalSec % 60
        val min = totalSec / 60
        return "%02d:%02d.%d".format(min, sec, tenths)
    }

    override fun onStop() {
        super.onStop()
        if (running) stopMonitoring()
    }
}
