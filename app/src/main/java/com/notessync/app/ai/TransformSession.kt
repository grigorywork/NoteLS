package com.notessync.app.ai

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class TransformPhase { INPUT, WORKING, APPLYING, RESULT, CLOSED }
data class TransformState(val phase: TransformPhase = TransformPhase.INPUT, val result: String = "", val problem: TransformProblem? = null)

/** One explicitly opened preview. No work starts until generate; no automatic application/retry. */
class TransformSession(
    private val config: () -> TransformConfig,
    private val secrets: TransformSecrets,
    private val transport: TransformTransport,
    private val current: suspend () -> Boolean,
    private val apply: suspend (String) -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val presented: TransformConfig = config()
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(TransformState())
    val state: StateFlow<TransformState> = mutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0L
    fun generate(input: String, mode: TransformMode) {
        if (mutable.value.phase == TransformPhase.CLOSED || mutable.value.phase == TransformPhase.WORKING || mutable.value.phase == TransformPhase.APPLYING) return
        val stamp = ++generation
        mutable.value = TransformState(TransformPhase.WORKING)
        work = scope.launch {
            try {
                val chosen = config()
                if (!chosen.enabled) throw TransformException(TransformProblem.DISABLED)
                if (chosen != presented) throw TransformException(TransformProblem.STALE)
                TransformLimits.input(input)
                if (!current()) throw TransformException(TransformProblem.ACCESS)
                val result = if (chosen.provider == AiProvider.LOCAL_RULES) withContext(Dispatchers.Default) { TransformLimits.local(input, mode) }
                else {
                    if (!TransformLimits.validModel(chosen.model)) throw TransformException(TransformProblem.MODEL_MISSING)
                    val key = secrets.read() ?: throw TransformException(TransformProblem.KEY_MISSING)
                    ensureActive()
                    if (config() != chosen || !current()) throw TransformException(TransformProblem.STALE)
                    withTimeout(45_000) { transport.transform(key, chosen.model, input, mode) }
                }
                ensureActive()
                if (stamp == generation && config() == chosen && current()) mutable.value = TransformState(TransformPhase.RESULT, TransformLimits.output(result))
                else if (stamp == generation) mutable.value = TransformState(problem = TransformProblem.STALE)
            } catch (_: TimeoutCancellationException) {
                if (stamp == generation) mutable.value = TransformState(problem = TransformProblem.TIMEOUT)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (stamp == generation) mutable.value = TransformState(problem = (error as? TransformException)?.problem ?: TransformProblem.PROVIDER)
            }
        }
    }
    fun replace(result: String) {
        if (mutable.value.phase != TransformPhase.RESULT) return
        val stamp = ++generation
        val previous = mutable.value
        mutable.value = previous.copy(phase = TransformPhase.APPLYING, problem = null)
        work = scope.launch {
            try {
                if (!config().enabled) throw TransformException(TransformProblem.DISABLED)
                if (config() != presented || !current()) throw TransformException(TransformProblem.STALE)
                TransformLimits.output(result)
                if (!apply(result)) throw TransformException(TransformProblem.STALE)
                if (stamp == generation) close()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (stamp == generation) mutable.value = previous.copy(problem = (error as? TransformException)?.problem ?: TransformProblem.SAVE)
            }
        }
    }
    fun close() {
        generation++
        mutable.value = TransformState(TransformPhase.CLOSED)
        work?.cancel(); scope.cancel()
    }
}
