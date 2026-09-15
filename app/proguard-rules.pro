# NeuTTS ProGuard rules
# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep TextToSpeech service classes
-keep class android.speech.tts.TextToSpeechService { *; }
-keep class android.speech.tts.SynthesisCallback { *; }
-keep class android.speech.tts.SynthesisRequest { *; }

# Keep ONNX Runtime classes
-keep class ai.onnxruntime.** { *; }

# Keep llama.cpp JNI classes
-keep class com.github.kingster.neutts.** { *; }

# Keep our service
-keep class com.github.kingster.neutts.NeuTTSService { *; }