package org.example.overlay.ptt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.example.overlay.audio.GatedAudioInput
import org.example.overlay.conversation.ConversationEvent
import org.example.overlay.conversation.ConversationState
import org.example.overlay.conversation.RealtimeConversationEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PushToTalkControllerTest {

    /**
     * Свой scope на тестовом планировщике, а не `backgroundScope`: фоновые корутины `runTest`
     * не выполняются по `advanceUntilIdle`, и контроллер бы просто не запустился.
     */
    private fun TestScope.testScope(): CoroutineScope =
        CoroutineScope(StandardTestDispatcher(testScheduler))

    private class FakeEngine : RealtimeConversationEngine {
        private val mutableEvents = MutableSharedFlow<ConversationEvent>(extraBufferCapacity = 64)
        private val mutableState = MutableStateFlow<ConversationState>(ConversationState.Idle)

        override val state: StateFlow<ConversationState> = mutableState
        override val events: SharedFlow<ConversationEvent> = mutableEvents

        var starts = 0
        var stops = 0
        var interrupts = 0
        var commits = 0
        var interruptResult = true

        override suspend fun start() {
            starts++
            emit(ConversationState.Active("test"))
        }

        override suspend fun stop() {
            stops++
            emit(ConversationState.Idle)
        }

        override suspend fun interrupt(): Boolean {
            interrupts++
            return interruptResult
        }

        override suspend fun commitInputAudio(): Boolean {
            commits++
            return true
        }

        suspend fun emit(newState: ConversationState) {
            mutableState.value = newState
            mutableEvents.emit(ConversationEvent.StateChanged(newState))
        }

        suspend fun emit(event: ConversationEvent) = mutableEvents.emit(event)
    }

    private class RecordingSignal : ActivationSignal {
        var opened = 0
        var closed = 0
        override suspend fun opened() { opened++ }
        override suspend fun closed() { closed++ }
    }

    private fun controller(
        engine: FakeEngine,
        gate: GatedAudioInput,
        signal: ActivationSignal,
        scope: kotlinx.coroutines.CoroutineScope,
        handsFree: Boolean = false,
        commitOnRelease: Boolean = false,
    ) = PushToTalkController(
        engine = engine,
        gate = gate,
        signal = signal,
        scope = scope,
        chunkBytes = 4800,
        chunkMs = 100,
        handsFree = { handsFree },
        commitOnRelease = { commitOnRelease },
    )

    @Test
    fun `удержание клавиши поднимает сессию и открывает микрофон`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val signal = RecordingSignal()
        val ptt = controller(engine, gate, signal, testScope())
        ptt.start()

        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        assertEquals(1, engine.starts)
        assertEquals(ActivationState.Listening, ptt.state.value)
        assertTrue(gate.isOpen)
        assertEquals(1, signal.opened)
    }

    @Test
    fun `отпускание клавиши дописывает хвост тишины и закрывает гейт`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val signal = RecordingSignal()
        val ptt = controller(engine, gate, signal, testScope())
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        ptt.onKeyUp()
        assertEquals(ActivationState.Thinking, ptt.state.value)
        // Хвост ещё пишется — гейт обязан оставаться открытым, иначе тишина не уйдёт в облако.
        assertTrue(gate.isOpen)

        testScheduler.advanceTimeBy(500)
        testScheduler.advanceUntilIdle()

        assertFalse(gate.isOpen)
        assertEquals(1, signal.closed)
        assertEquals(0, engine.commits)
    }

    @Test
    fun `при включённом запасном пути ход закрывается явным commit`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope(), commitOnRelease = true)
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        ptt.onKeyUp()
        testScheduler.advanceTimeBy(500)
        testScheduler.advanceUntilIdle()

        assertEquals(1, engine.commits)
    }

    @Test
    fun `начало ответа переводит в Speaking и закрывает микрофон`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope())
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        engine.emit(ConversationEvent.ResponseStarted(1))
        testScheduler.advanceUntilIdle()

        assertEquals(ActivationState.Speaking, ptt.state.value)
        assertFalse(gate.isOpen)
    }

    @Test
    fun `клавиша во время ответа прерывает его и снова открывает микрофон`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope())
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()
        engine.emit(ConversationEvent.ResponseStarted(1))
        testScheduler.advanceUntilIdle()

        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        assertEquals(1, engine.interrupts)
        assertEquals(ActivationState.Listening, ptt.state.value)
        assertTrue(gate.isOpen)
    }

    @Test
    fun `в push-to-talk окно после ответа не открывает микрофон`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope())
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        engine.emit(ConversationEvent.ResponseCompleted)
        // runCurrent, а не advanceUntilIdle: последний прокрутил бы и таймер окна follow-up.
        testScheduler.runCurrent()

        assertIs<ActivationState.FollowUp>(ptt.state.value)
        assertFalse(gate.isOpen)
    }

    @Test
    fun `в hands-free окно после ответа открывает микрофон`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope(), handsFree = true)
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()

        engine.emit(ConversationEvent.ResponseCompleted)
        testScheduler.runCurrent()

        assertTrue(gate.isOpen)
    }

    @Test
    fun `тишина дольше окна закрывает сокет - открытая сессия стоит денег`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope())
        ptt.start()
        ptt.onKeyDown()
        testScheduler.advanceUntilIdle()
        engine.emit(ConversationEvent.ResponseCompleted)
        testScheduler.advanceUntilIdle()

        testScheduler.advanceTimeBy(4_000)
        testScheduler.advanceUntilIdle()

        assertEquals(ActivationState.Idle, ptt.state.value)
        assertEquals(1, engine.stops)
    }

    @Test
    fun `отпускание клавиши до подъёма сессии не оставляет микрофон открытым`() = runTest {
        val engine = FakeEngine()
        val gate = GatedAudioInput(4)
        val ptt = controller(engine, gate, RecordingSignal(), testScope())
        ptt.start()

        ptt.onKeyDown()
        ptt.onKeyUp()
        testScheduler.runCurrent()

        assertFalse(gate.isOpen)
        assertIs<ActivationState.FollowUp>(ptt.state.value)
    }
}
