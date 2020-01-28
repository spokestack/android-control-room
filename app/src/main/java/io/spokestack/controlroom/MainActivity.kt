package io.spokestack.controlroom

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import io.spokestack.spokestack.tts.SynthesisRequest
import io.spokestack.spokestack.tts.TTSEvent
import io.spokestack.spokestack.tts.TTSListener
import io.spokestack.spokestack.tts.TTSManager
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream

private const val PREF_NAME = "AppPrefs"
private const val versionKey = "versionCode"
private const val nonexistent = -1

class MainActivity : AppCompatActivity(), OnSpeechEventListener, TTSListener {
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        echoTranscript.setOnCheckedChangeListener(::echoChanged)

        // Spokestack setup
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
            .useProfile("io.spokestack.spokestack.profile.TFWakewordAndroidASR")
            .setProperty("wake-detect-path", "$cacheDir/detect.lite")
            .setProperty("wake-encode-path", "$cacheDir/encode.lite")
            .setProperty("wake-filter-path", "$cacheDir/filter.lite")
            .setAndroidContext(applicationContext)
            .addOnSpeechEventListener(this)
            .setProperty("trace-level", SpeechContext.TraceLevel.INFO.value())
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
        listOf("detect.lite", "encode.lite", "filter.lite").forEach(::cacheAsset)
    }

    private fun cacheAsset(modelName: String) {
        val filterFile = File("$cacheDir/$modelName")
        val inputStream = assets.open(modelName)
        val size = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        val fos = FileOutputStream(filterFile)
        fos.write(buffer)
        fos.close()
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
}
