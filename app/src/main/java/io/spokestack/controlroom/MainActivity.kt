package io.spokestack.controlroom

import android.Manifest
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.spokestack.spokestack.OnSpeechEventListener
import io.spokestack.spokestack.SpeechContext
import io.spokestack.spokestack.SpeechPipeline
import io.spokestack.spokestack.nlu.TraceListener
import io.spokestack.spokestack.tts.SynthesisRequest
import io.spokestack.spokestack.tts.TTSEvent
import io.spokestack.spokestack.tts.TTSListener
import io.spokestack.spokestack.tts.TTSManager
import io.spokestack.spokestack.util.EventTracer
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val PREF_NAME = "AppPrefs"
private const val versionKey = "versionCode"
private const val nonexistent = -1

class MainActivity : AppCompatActivity(), OnSpeechEventListener, TTSListener, TraceListener {
    private val logTag = javaClass.simpleName
    private val audioPermission = 1337
    private val redColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getColor(applicationContext, R.color.red)
    }
    private val greenColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getColor(applicationContext, R.color.green)
    }

    private var pipeline: SpeechPipeline? = null
    private var tts: TTSManager? = null

    private val scorerPath: String by lazy { "$externalCacheDir/deepspeech.scorer" }
    private lateinit var downloadManager: DownloadManager
    private var scorerDownloadId: Long = 0
    private var downloadStart: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        echoTranscript.setOnCheckedChangeListener(::echoChanged)

        // Spokestack setup
//        if (!checkForScorer()) {
//            downloadScorer()
//        } else {
//            Toast.makeText(applicationContext, "Scorer model found", Toast.LENGTH_LONG).show()
//            if (this.pipeline == null && checkMicPermission()) {
//                buildPipeline()
//            }
//            buildTTS()
//        }

        if (this.pipeline == null && checkMicPermission()) {
            buildPipeline()
        }
        buildTTS()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun echoChanged(echoButton: CompoundButton, checked: Boolean) {
        if (checked) {
            ttsInput.setText(transcriptField.text)
        }
    }

    private fun checkMicPermission(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            audioPermission
        )
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            audioPermission -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED
                ) {
                    buildPipeline()
                } else {
                    Log.w(logTag, "Record permission not granted; voice control disabled!")
                }
                return
            }
            else -> {
                // do nothing
            }
        }
    }

    private fun buildPipeline() {
        checkForModels()

        pipeline = SpeechPipeline.Builder()
            .useProfile("io.spokestack.spokestack.profile.TFWakewordMozillaASR")
            .setProperty("wake-detect-path", "$cacheDir/detect.lite")
            .setProperty("wake-encode-path", "$cacheDir/encode.lite")
            .setProperty("wake-filter-path", "$cacheDir/filter.lite")
            .setProperty("mozilla-model-path", "$cacheDir/deepspeech.tflite")
//            .setProperty("mozilla-scorer-path", scorerPath)
            .addOnSpeechEventListener(this)
            .setProperty("trace-level", EventTracer.Level.DEBUG.value())
            .build()

        pipeline?.start()
    }

    private fun checkForModels() {
        if (!modelsCached()) {
            decompressModels()
        } else {
            val currentVersionCode = BuildConfig.VERSION_CODE
            val prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val savedVersionCode = prefs.getInt(versionKey, nonexistent)

            if (currentVersionCode != savedVersionCode) {
                decompressModels()

                // Update the shared preferences with the current version code
                prefs.edit().putInt(versionKey, currentVersionCode).apply()
            }
        }
    }

    private fun modelsCached(): Boolean {
        val filterName = "filter.lite"
        val filterFile = File("$cacheDir/$filterName")
        return filterFile.exists()
    }

    private fun decompressModels() {
        listOf(
            "detect.lite", "encode.lite", "filter.lite", "deepspeech.tflite", "vocab.txt"
        )
            .forEach(::cacheAsset)
    }

    private fun cacheAsset(assetName: String) {
        val assetFile = File("$cacheDir/$assetName")
        try {
            val inputStream = assets.open(assetName)
            val size = inputStream.available()
            val buffer = ByteArray(size)
            inputStream.read(buffer)
            inputStream.close()
            val fos = FileOutputStream(assetFile)
            fos.write(buffer)
            fos.close()
        } catch (ioe: IOException) {
            ioe.printStackTrace()
            return
        }
    }

    private fun buildTTS() {
        if (this.tts == null) {
            this.tts = TTSManager.Builder()
                .setTTSServiceClass("io.spokestack.spokestack.tts.SpokestackTTSService")
                .setOutputClass("io.spokestack.spokestack.tts.SpokestackTTSOutput")
                .setProperty("spokestack-id", "f0bc990c-e9db-4a0c-a2b1-6a6395a3d97e")
                .setProperty(
                    "spokestack-secret",
                    "5BD5483F573D691A15CFA493C1782F451D4BD666E39A9E7B2EBE287E6A72C6B6"
                )
                .addTTSListener(this)
                .setAndroidContext(applicationContext)
                .setLifecycle(lifecycle)
                .build()
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun activateAsrTapped(view: View) {
        pipeline?.activate()
    }

    @Suppress("UNUSED_PARAMETER")
    fun speakTapped(view: View) {
        val request = SynthesisRequest.Builder(ttsInput.text).build()
        tts?.synthesize(request)
        setTTSProcessing(true)
    }

    private fun setVadActive(active: Boolean) {
        runOnUiThread {
            if (active) {
                vadActive.text = getText(R.string.active)
                vadActive.setTextColor(greenColor)
            } else {
                vadActive.text = getText(R.string.inactive)
                vadActive.setTextColor(redColor)
            }
        }
    }

    private fun setAsrActive(active: Boolean) {
        runOnUiThread {
            if (active) {
                activateAsr.isEnabled = false
                asrActive.text = getText(R.string.active)
                asrActive.setTextColor(greenColor)
            } else {
                activateAsr.isEnabled = true
                asrActive.text = getText(R.string.inactive)
                asrActive.setTextColor(redColor)
            }
        }
    }

    private fun transcribe(transcript: String) {
        runOnUiThread {
            transcriptField.text = transcript
            if (echoTranscript.isChecked) {
                ttsInput.setText(transcript)
            }
        }
    }

    private fun handleTrace(message: String) {
        when (message.removePrefix("vad: ")) {
            "true" -> setVadActive(true)
            "false" -> setVadActive(false)
            // not a VAD trace; do nothing
            else -> return
        }
    }

    // OnSpeechEventListener implementation
    override fun onEvent(event: SpeechContext.Event?, context: SpeechContext?) {
        when (event) {
            SpeechContext.Event.ACTIVATE -> setAsrActive(true)
            SpeechContext.Event.DEACTIVATE -> setAsrActive(false)
            SpeechContext.Event.RECOGNIZE -> context?.transcript?.let { transcribe(it) }
            SpeechContext.Event.TIMEOUT -> errorToast("ASR timeout")
            SpeechContext.Event.ERROR -> context?.error?.message?.let { errorToast(it) }
            SpeechContext.Event.TRACE -> context?.message?.let { handleTrace(it) }
        }
    }

    // TTSListener implementation
    override fun eventReceived(event: TTSEvent?) {
        when (event?.type) {
            TTSEvent.Type.ERROR -> event.error.message?.let { errorToast("TTS error: $it") }
            TTSEvent.Type.AUDIO_AVAILABLE -> setTTSProcessing(false)
        }
    }

    private fun errorToast(message: String) {
        val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
        toast.show()
    }

    private fun setTTSProcessing(processing: Boolean) {
        runOnUiThread {
            if (processing) {
                speakButton.isEnabled = false
                speakButton.text = getString(R.string.processing)
            } else {
                speakButton.isEnabled = true
                speakButton.text = getString(R.string.speak)
            }
        }
    }

    // ==================
    // Deepspeech scorer downloading
    // ==================

    private fun checkForScorer(): Boolean {
        return File(scorerPath).exists()
    }

    private fun downloadScorer() {
        val scorerUrl =
            Uri.parse("https://github.com/mozilla/DeepSpeech/releases/download/v0.7.0/deepspeech-0.7.0-models.scorer")
        val scorerDestFile = Uri.parse("file://$scorerPath")
        this.activateAsr.isEnabled = false
        val noModel = Toast.makeText(
            applicationContext,
            "No scorer found. Triggering download ...",
            Toast.LENGTH_LONG
        )
        noModel.show()
        this.downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val request = DownloadManager.Request(scorerUrl)
        request.setTitle("DeepSpeech scorer")
        request.setDescription("DeepSpeech scorer")
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationUri(scorerDestFile)
        this.scorerDownloadId = this.downloadManager.enqueue(request)
        this.downloadStart = SystemClock.elapsedRealtime()
        applicationContext.registerReceiver(
            ScorerReceiver(),
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        )
    }

    inner class ScorerReceiver : BroadcastReceiver() {
        override fun onReceive(
            context: Context,
            intent: Intent
        ) {
            val action = intent.action
            if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                val downloadId =
                    intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, 0)
                val query = DownloadManager.Query()
                query.setFilterById(downloadId)
                val c: Cursor = this@MainActivity.downloadManager.query(query)
                if (c.moveToFirst()) {
                    val columnIndex =
                        c.getColumnIndex(DownloadManager.COLUMN_STATUS)
                    if (DownloadManager.STATUS_SUCCESSFUL == c.getInt(columnIndex)) {
                        val downloadSecs = (SystemClock.elapsedRealtime() -
                                this@MainActivity.downloadStart) / 1000
                        Toast.makeText(
                            applicationContext,
                            "Download took $downloadSecs seconds",
                            Toast.LENGTH_LONG
                        ).show()
                        buildPipeline()
                        buildTTS()
                        this@MainActivity.activateAsr.isEnabled = true
                    }
                }
            }
        }

    }
}
