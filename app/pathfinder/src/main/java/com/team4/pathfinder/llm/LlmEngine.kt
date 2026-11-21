package com.team4.pathfinder.llm

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmModule
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** One message in a Llama 3 chat. role is "system", "user" or "assistant". */
data class Turn(val role: String, val text: String)

data class Reply(
    val text: String,
    val totalMs: Long,
    val firstTokenMs: Long,
    val tokens: Int,
    val tokensPerSecond: Double,
    val cutOff: Boolean,
)

/**
 * The only class that talks to ExecuTorch. The rest of the app calls [chat] with plain turns.
 * One request at a time; every request starts from a fresh context.
 */
class LlmEngine(val modelPath: String, val tokenizerPath: String) {
    private var module: LlmModule? = null
    private val lock = Mutex()

    val isLoaded: Boolean get() = module != null

    fun filesPresent(): Boolean = File(modelPath).canRead() && File(tokenizerPath).canRead()

    /** Loads the .pte (memory-mapped, so it is quick). Returns milliseconds. */
    suspend fun load(): Long = lock.withLock {
        withContext(Dispatchers.IO) {
            check(filesPresent()) { "Model files not found in ${File(modelPath).parent}. Push them with: bash export/phase1.sh push 1B" }
            val t0 = SystemClock.elapsedRealtime()
            val m = LlmModule(TEXT_MODEL, modelPath, tokenizerPath, TEMPERATURE)
            m.load() // ExecuTorch 1.5.1 throws on failure (no return code)
            module = m
            SystemClock.elapsedRealtime() - t0
        }
    }

    /**
     * Runs one chat request. [onText] receives the reply so far, on the generation thread.
     * Stops at the end-of-turn token, after [maxTokens] tokens, or after [timeoutMs].
     */
    suspend fun chat(turns: List<Turn>, maxTokens: Int = 200, timeoutMs: Long = DEFAULT_TIMEOUT_MS, onText: (String) -> Unit = {}): Reply = lock.withLock {
        val m = checkNotNull(module) { "Load the model first" }
        withContext(Dispatchers.IO) {
            val out = StringBuilder()
            val ended = AtomicBoolean(false)
            var tokens = 0
            var inHeader = false
            var firstAt = -1L
            var cutOff = false
            var error: String? = null
            val t0 = SystemClock.elapsedRealtime()
            val callback = object : LlmCallback {
                override fun onResult(result: String) {
                    if (ended.get()) return
                    when (result) {
                        "<|eot_id|>", "<|end_of_text|>" -> { if (ended.compareAndSet(false, true)) m.stop(); return }
                        "<|start_header_id|>" -> { inHeader = true; return }
                        "<|end_header_id|>" -> { inHeader = false; return }
                    }
                    if (inHeader) return
                    if (firstAt < 0) firstAt = SystemClock.elapsedRealtime() - t0
                    tokens++
                    out.append(result)
                    onText(out.toString().trimStart())
                    if (tokens >= maxTokens && ended.compareAndSet(false, true)) { cutOff = true; m.stop() }
                }

                override fun onError(errorCode: Int, message: String) {
                    error = "ExecuTorch error $errorCode: $message"
                }
            }
            m.resetContext()
            val watchdog = launch {
                delay(timeoutMs)
                if (ended.compareAndSet(false, true)) { cutOff = true; m.stop() }
            }
            try {
                m.generate(format(turns), SEQ_LEN, callback, false)
            } finally {
                watchdog.cancel()
            }
            error?.let { throw IllegalStateException(it) }
            val total = SystemClock.elapsedRealtime() - t0
            val genMs = (total - maxOf(firstAt, 0L)).coerceAtLeast(1L)
            Reply(out.toString().trim(), total, firstAt, tokens, tokens * 1000.0 / genMs, cutOff)
        }
    }

    companion object {
        const val DEFAULT_DIR = "/data/local/tmp/llama"

        /** x86 emulators run the model ~10x slower than an Arm phone (measured 1.2 vs 14.4 tok/s). */
        val isEmulator: Boolean = android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.PRODUCT.startsWith("sdk_gphone")

        /** A phone answers in ~10-25 s; the emulator gets 5 minutes so its slow replies can still finish. */
        val DEFAULT_TIMEOUT_MS: Long = if (isEmulator) 300_000 else 60_000
        private const val TEXT_MODEL = 1 // ExecuTorch model category for text-only LLMs on XNNPACK
        private const val TEMPERATURE = 0.0f
        private const val SEQ_LEN = 1024 // prompt + reply tokens; the .pte allows up to 2048

        /** Llama 3 chat template, the same layout the ExecuTorch demo app uses. */
        fun format(turns: List<Turn>): String = buildString {
            append("<|begin_of_text|>")
            for (t in turns) append("<|start_header_id|>${t.role}<|end_header_id|>\n\n${t.text}<|eot_id|>")
            append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }
}
