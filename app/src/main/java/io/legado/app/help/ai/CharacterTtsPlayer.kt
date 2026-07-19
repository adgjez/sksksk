package io.legado.app.help.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import io.legado.app.utils.LogUtils
import splitties.init.appCtx
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 角色朗读播放器：用 Android 系统 TTS 按 voice 名播每段。
 *
 * 注意：OpenAI/Azure 风格的 voice 名（zh-CN-XiaoxiaoNeural 等）需要用户
 * 在 Android 系统设置 → 语言和输入法 → 文字转语音（TTS）里装对应语音包。
 * 找不到时回退到默认 voice。
 */
object CharacterTtsPlayer {

    private val tts: TextToSpeech? by lazy {
        val atomic = AtomicBoolean(false)
        val tts = TextToSpeech(appCtx) { status ->
            atomic.set(status == TextToSpeech.SUCCESS)
            if (!atomic.get()) LogUtils.e("CharacterTtsPlayer", "TTS init failed: $status")
        }
        // 最多等 1.5 秒 init
        val deadline = System.currentTimeMillis() + 1500
        while (!atomic.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        tts
    }

    /** 把 voice name 解析到已安装的 Android Voice，找不到返回 null。 */
    private fun resolveVoice(name: String): Voice? {
        if (name.isBlank()) return null
        val voices = tts?.voices ?: return null
        return voices.firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: voices.firstOrNull { it.name.contains(name, ignoreCase = true) }
            ?: voices.firstOrNull { v ->
                // 用 locale+quality 匹配
                val n = name.lowercase()
                v.locale.toLanguageTag().lowercase().contains(n.substringBefore("-").lowercase()) &&
                        (v.name.lowercase().contains(n.substringAfterLast('-', "").lowercase()))
            }
    }

    /** 同步播一段队列。 */
    fun play(segments: List<Pair<String, String>>) {
        val engine = tts ?: return
        if (segments.isEmpty()) return
        engine.stop()
        engine.setSpeechRate(1.0f)
        for ((text, voiceName) in segments) {
            if (text.isBlank()) continue
            resolveVoice(voiceName)?.let { engine.voice = it }
            // QUEUE_ADD 自动接上一段末尾
            engine.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        }
    }

    /** 停止当前播放。 */
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
    }

    /** 列出本机已装的 voice name（用于 debug / 提示用户装包）。 */
    fun availableVoices(): List<String> = tts?.voices?.map { it.name } ?: emptyList()
}
