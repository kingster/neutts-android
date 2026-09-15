package com.example.neutts

import android.os.Bundle
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.AdapterView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Main Activity for NeuTTS Air Engine.
 *
 * On first launch, extracts the bundled GGUF backbone + NeuCodec ONNX decoder
 * from assets into app-private storage, loads the sample reference voice, and
 * offers a "test synthesis" button that runs the full pipeline end to end via
 * [NeuTtsEngine] and plays the result through [AudioTrack].
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private lateinit var statusText: TextView
    private lateinit var editText: EditText
    private lateinit var testButton: Button
    private lateinit var voiceSpinner: Spinner
    private lateinit var backboneSpinner: Spinner

    private var engine: NeuTtsEngine? = null
    private var referenceVoice: ReferenceVoice? = null
    private var selectedVoiceName: String = NeuTtsEngine.BUNDLED_VOICES.first()
    private var selectedBackbone: Backbone = NeuTtsEngine.BACKBONES.first()

    // Bumped on every backbone (re)load; a background load thread checks this
    // before publishing its result so a superseded (e.g. user switched
    // backbone again mid-download) load can't clobber a newer one.
    private val loadGeneration = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("neutts_config", MODE_PRIVATE)
        selectedVoiceName = prefs.getString("voice_name", NeuTtsEngine.BUNDLED_VOICES.first())
            ?: NeuTtsEngine.BUNDLED_VOICES.first()
        val backboneId = prefs.getString("backbone_id", NeuTtsEngine.BACKBONES.first().id)
        selectedBackbone = NeuTtsEngine.BACKBONES.firstOrNull { it.id == backboneId }
            ?: NeuTtsEngine.BACKBONES.first()
        setupUI()
        initEngineInBackground()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.close()
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(48, bars.top + 48, 48, 48)
            insets
        }

        val titleText = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 24f
            typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            setPadding(0, 0, 0, 32)
        }

        statusText = TextView(this).apply {
            text = "Loading models…"
            textSize = 16f
            setPadding(0, 0, 0, 24)
        }

        val voiceHeader = TextView(this).apply {
            text = "Voice"
            textSize = 18f
            typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            setPadding(0, 8, 0, 16)
        }

        voiceSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, NeuTtsEngine.BUNDLED_VOICES
            )
            setSelection(NeuTtsEngine.BUNDLED_VOICES.indexOf(selectedVoiceName).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    onVoiceSelected(NeuTtsEngine.BUNDLED_VOICES[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val backboneHeader = TextView(this).apply {
            text = "Backbone"
            textSize = 18f
            typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            setPadding(0, 8, 0, 16)
        }

        val backboneNames = NeuTtsEngine.BACKBONES.map { it.displayName }
        backboneSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, backboneNames)
            setSelection(NeuTtsEngine.BACKBONES.indexOf(selectedBackbone).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    onBackboneSelected(NeuTtsEngine.BACKBONES[position])
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }

        val testHeader = TextView(this).apply {
            text = "Test Synthesis"
            textSize = 18f
            typeface = Typeface.defaultFromStyle(Typeface.BOLD)
            setPadding(0, 24, 0, 16)
        }

        editText = EditText(this).apply {
            hint = getString(R.string.enter_text)
            setPadding(16, 16, 16, 16)
        }

        testButton = Button(this).apply {
            text = getString(R.string.test_synthesis)
            isEnabled = false
            setOnClickListener { performTestSynthesis() }
        }

        rootLayout.addView(titleText)
        rootLayout.addView(statusText)
        rootLayout.addView(voiceHeader)
        rootLayout.addView(voiceSpinner)
        rootLayout.addView(backboneHeader)
        rootLayout.addView(backboneSpinner)
        rootLayout.addView(testHeader)
        rootLayout.addView(editText)
        rootLayout.addView(testButton)

        setContentView(rootLayout)
    }

    private fun initEngineInBackground() {
        loadBackbone(selectedBackbone, closePrevious = null)
    }

    private fun onBackboneSelected(backbone: Backbone) {
        if (backbone.id == selectedBackbone.id) return
        selectedBackbone = backbone
        getSharedPreferences("neutts_config", MODE_PRIVATE).edit()
            .putString("backbone_id", backbone.id)
            .apply()
        loadBackbone(backbone, closePrevious = engine)
        engine = null
    }

    /**
     * Downloads (if needed) and loads [backbone], reporting progress and
     * publishing the result to [engine]/[referenceVoice]. If [onBackboneSelected]
     * fires again before this completes, [loadGeneration] will have moved on
     * and this call's result is discarded instead of racing the newer one.
     */
    private fun loadBackbone(backbone: Backbone, closePrevious: NeuTtsEngine?) {
        val myGeneration = loadGeneration.incrementAndGet()
        testButton.isEnabled = false
        Thread {
            try {
                closePrevious?.close()
                val (gguf, onnx) = NeuTtsEngine.ensureModelsDownloaded(this, backbone) { label, progress ->
                    if (loadGeneration.get() != myGeneration) return@ensureModelsDownloaded
                    runOnUiThread { statusText.text = "Downloading $label… ${(progress * 100).roundToInt()}%" }
                }
                val newEngine = NeuTtsEngine.create(this, backbone, gguf.absolutePath, onnx.absolutePath)
                val reference = NeuTtsEngine.loadBundledReferenceVoice(this, selectedVoiceName)

                if (loadGeneration.get() != myGeneration) {
                    newEngine.close() // superseded by a newer backbone selection; discard
                    return@Thread
                }
                engine = newEngine
                referenceVoice = reference
                runOnUiThread {
                    statusText.text = getString(R.string.tts_ready)
                    testButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Engine load failed: ${e.message}", e)
                if (loadGeneration.get() == myGeneration) {
                    runOnUiThread { statusText.text = "Failed to load models: ${e.message}" }
                }
            }
        }.start()
    }

    private fun onVoiceSelected(name: String) {
        if (name == selectedVoiceName) return
        selectedVoiceName = name
        getSharedPreferences("neutts_config", MODE_PRIVATE).edit()
            .putString("voice_name", name)
            .apply()

        if (engine == null) return // still loading; initEngineInBackground will pick this up
        statusText.text = "Loading voice…"
        testButton.isEnabled = false
        Thread {
            try {
                val reference = NeuTtsEngine.loadBundledReferenceVoice(this, name)
                referenceVoice = reference
                runOnUiThread {
                    statusText.text = getString(R.string.tts_ready)
                    testButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Voice load failed: ${e.message}", e)
                runOnUiThread { statusText.text = "Failed to load voice: ${e.message}" }
            }
        }.start()
    }

    private fun performTestSynthesis() {
        val text = editText.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Please enter some text", Toast.LENGTH_SHORT).show()
            return
        }

        val activeEngine = engine
        val reference = referenceVoice
        if (activeEngine == null || reference == null) {
            Toast.makeText(this, "Engine not ready yet", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = getString(R.string.synthesis_started)

        Thread {
            try {
                val startMs = System.currentTimeMillis()
                val pcm = activeEngine.synthesize(text, reference)
                val synthesisMs = System.currentTimeMillis() - startMs
                playPcm(pcm)
                runOnUiThread {
                    statusText.text = "${getString(R.string.synthesis_complete)} (${synthesisMs} ms)"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synthesis error: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(this, "Synthesis error: ${e.message}", Toast.LENGTH_LONG).show()
                    statusText.text = getString(R.string.playback_error)
                }
            }
        }.start()
    }

    private fun playPcm(pcm: ShortArray) {
        val sampleRate = 24000
        val bufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(bufferSize, pcm.size * 2))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        if (audioTrack.state == AudioTrack.STATE_UNINITIALIZED) {
            Log.e(TAG, "AudioTrack failed to initialize")
            return
        }

        audioTrack.write(pcm, 0, pcm.size)
        audioTrack.play()
        Thread.sleep((pcm.size * 1000L) / sampleRate + 200)
        audioTrack.release()
    }
}
