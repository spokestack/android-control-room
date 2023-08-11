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
import io.spokestack.spokestack.*
import io.spokestack.spokestack.nlu.NLUResult
import io.spokestack.spokestack.tts.SynthesisRequest
import io.spokestack.spokestack.tts.TTSEvent
import io.spokestack.spokestack.util.EventTracer
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream

private const val PREF_NAME = "AppPrefs"
private const val versionKey = "versionCode"
private const val nonexistent = -1

class MainActivity : AppCompatActivity() {
    private val logTag = javaClass.simpleName

    // a sentinel value we'll use to verify we have the proper permissions to use Spokestack to
    // record audio
    private val audioPermission = 1337

    private val redColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getColor(applicationContext, R.color.red)
    }
    private val greenColor: Int by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getColor(applicationContext, R.color.green)
    }

    // the Spokestack instance itself and a listener to receive events from its modules
    private lateinit var spokestack: Spokestack
    private val listener: SpokestackAdapter = SpokestackListener()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Spokestack setup
        spokestack = buildSpokestack()
        checkMicPermission()
        if (checkMicPermission()) {
            spokestack.start()
        }

        echoTranscript.setOnCheckedChangeListener(::echoChanged)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun echoChanged(echoButton: CompoundButton, checked: Boolean) {
        if (checked) {
            ttsInput.setText(transcriptField.text)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun activateAsrTapped(view: View) {
        spokestack.activate()
    }

    @Suppress("UNUSED_PARAMETER")
    fun speakTapped(view: View) {
        val request = SynthesisRequest.Builder(ttsInput.text)
            .withMode(SynthesisRequest.Mode.MARKDOWN)
            .build()
        spokestack.synthesize(request)
        setTTSProcessing(true)
    }

    @Suppress("UNUSED_PARAMETER")
    fun classifyTapped(view: View) {
        spokestack.classify(ttsInput.text.toString())
    }

    private fun buildSpokestack(): Spokestack {
        // We're going to use TensorFlow Lite models for wakeword detection to demonstrate how
        // you'd set up the speech pipeline to expect them. Compressing them in your assets folder
        // and decompressing them to the cache folder if they're absent is one way to keep app size
        // down while making the models available at runtime. Another way would be to download the
        // models from a CDN and cache them in a similar fashion.
        // You don't need to worry about this step if you're not using TFLite for wakeword
        // detection (see the io.spokestack.spokestack.profile package for descriptions of
        // the various pre-configured profiles available).
        // See the README for links to the original files.
        checkForModels()

        // On-device ASR via the "TFWakewordAndroidASR" profile is enabled by the Spokestack
        // builder by default. Note that it may be unavailable on older devices, so you may wish
        // to call `SpeechRecognizer.isRecognitionAvailable(Context)` and use a different ASR if
        // it is unavailable, or use the Android-provided ASR only for demos.
        return Spokestack.Builder()
            // wakeword models
            // These must be downloaded separately (see the README).
            // To disable wakeword detection, delete the lines pointing to
            // wakeword paths and uncomment the following:
            // .withoutWakeword()
            .setProperty("wake-detect-path", "$cacheDir/detect.tflite")
            .setProperty("wake-encode-path", "$cacheDir/encode.tflite")
            .setProperty("wake-filter-path", "$cacheDir/filter.tflite")
            // NLU
            // You'll need NLU models to use this component; see the README for more
            .setProperty("nlu-model-path", "$cacheDir/nlu.tflite")
            .setProperty("nlu-metadata-path", "$cacheDir/nlu.json")
            .setProperty("wordpiece-vocab-path", "$cacheDir/vocab.txt")
            .setProperty("trace-level", EventTracer.Level.DEBUG.value())
            // TTS
            .setProperty("spokestack-id", "f0bc990c-e9db-4a0c-a2b1-6a6395a3d97e")
            .setProperty(
                "spokestack-secret",
                "5BD5483F573D691A15CFA493C1782F451D4BD666E39A9E7B2EBE287E6A72C6B6"
            )
            .withAndroidContext(applicationContext)
            // make sure we receive Spokestack events
            .addListener(listener)
            .build()
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
        // We'll use the presence of one of the wakeword models as a proxy for everything being
        // decompressed since we do it all in the same step. You may wish to be more thorough.
        val filterName = "filter.tflite"
        val filterFile = File("$cacheDir/$filterName")
        return filterFile.exists()
    }

    private fun decompressModels() {
        listOf(
            "detect.tflite",
            "encode.tflite",
            "filter.tflite",
            "nlu.tflite",
            "nlu.json",
            "vocab.txt"
        ).forEach(::cacheAsset)
    }

    private fun cacheAsset(fileName: String) {
        val cachedFile = File("$cacheDir/$fileName")
        val inputStream = assets.open(fileName)
        val size = inputStream.available()
        val buffer = ByteArray(size)
        inputStream.read(buffer)
        inputStream.close()
        val fos = FileOutputStream(cachedFile)
        fos.write(buffer)
        fos.close()
    }

    private fun checkMicPermission(): Boolean {
        // On API levels >= 23, users can revoke permissions at any time, and API levels >= 26
        // require the RECORD_AUDIO permission to be requested at runtime, so we'll need
        // to verify it on launch
        val recordPerm = Manifest.permission.RECORD_AUDIO
        val granted = PackageManager.PERMISSION_GRANTED
        if (ContextCompat.checkSelfPermission(this, recordPerm) == granted) {
            return true
        }
        ActivityCompat.requestPermissions(this, arrayOf(recordPerm), audioPermission)
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val granted = PackageManager.PERMISSION_GRANTED
        // respond to the permission request's asynchronous result
        when (requestCode) {
            audioPermission -> {
                if (grantResults.isNotEmpty() && grantResults[0] == granted) {
                    // if you request permissions when, e.g., a microphone button is tapped, you
                    // may wish to call `activate()` here instead of `start()`
                    spokestack.start()
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

    private fun setNluResults(result: NLUResult) {
        result.error?.printStackTrace()
        runOnUiThread {
            intentResult.text = result.intent
            val slotDisplay = StringBuilder()
            result.slots?.forEach { (name, slot) ->
                slotDisplay.append(name)
                slotDisplay.append(": ")
                slotDisplay.append(slot.value)
                slotDisplay.append("\n")
            }
            slotField.setText(slotDisplay.toString())
        }
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
            else -> if (!message.startsWith("agc", false)) {
                println(message)
            }
        }
    }

    private fun errorToast(message: String) {
        runOnUiThread {
            val toast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT)
            toast.show()
        }
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

    inner class SpokestackListener : SpokestackAdapter() {

        override fun onEvent(event: SpeechContext.Event, context: SpeechContext) {
            when (event) {
                SpeechContext.Event.ACTIVATE -> setAsrActive(true)
                SpeechContext.Event.DEACTIVATE -> setAsrActive(false)
                SpeechContext.Event.PARTIAL_RECOGNIZE, SpeechContext.Event.RECOGNIZE ->
                    context.transcript?.let { transcribe(it) }
                SpeechContext.Event.TIMEOUT -> errorToast("ASR timeout")
                SpeechContext.Event.ERROR -> context.error?.message?.let {
                    context.error.printStackTrace()
                    errorToast("${context.error.javaClass}: $it")
                }
                SpeechContext.Event.TRACE -> context.message?.let { handleTrace(it) }
            }
        }

        // NLU
        override fun call(result: NLUResult) {
            setNluResults(result)
            // In an app without an explicit dialogue manager, this is where you would generate
            // responses and call spokestack.synthesize()
        }

        // TTS listener implementation
        // We're letting Spokestack automatically handle TTS playback,
        // so this isn't strictly necessary,
        // but it's here to provide error feedback
        override fun ttsEvent(event: TTSEvent) {
            when (event.type) {
                TTSEvent.Type.ERROR -> Log.w(logTag, event.error)
                // If you're managing playback yourself, this is where you'd receive the URL to your
                // synthesized audio
                TTSEvent.Type.AUDIO_AVAILABLE -> {
                    Log.i(logTag, "Audio received: ${event.ttsResponse.audioUri}")
                    setTTSProcessing(false)
                }
                TTSEvent.Type.PLAYBACK_COMPLETE -> Log.i(logTag, "TTS playback complete")
                else -> {
                    // do nothing
                }
            }
        }

        override fun onTrace(level: EventTracer.Level, message: String) {
            when (level) {
                EventTracer.Level.ERROR -> Log.e(logTag, message)
                EventTracer.Level.DEBUG -> Log.d(logTag, message)
                EventTracer.Level.INFO -> Log.i(logTag, message)
                EventTracer.Level.WARN -> Log.w(logTag, message)
                else -> Log.v(logTag, message)
            }
        }
    }
}
