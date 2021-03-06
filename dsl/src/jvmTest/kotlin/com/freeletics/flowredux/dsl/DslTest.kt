package com.freeletics.flowredux.dsl

import com.freeletics.flow.testovertime.record
import com.freeletics.flowredux.FlowReduxLogger
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert
import org.junit.Ignore
import org.junit.Test
import java.util.concurrent.TimeUnit

// TODO fix FlowRecorder and migrate tast from .testOverTime() to .record()
class DslTest {

    private val timeout20Milliseconds = TimeoutConfig(
        timeout = 20,
        timeoutTimeUnit = TimeUnit.MILLISECONDS
    )

    @Test
    fun `empty statemachine just emits initial state`() {
        val sm = StateMachine { }
        val state = sm.state.record()

        state shouldEmitNext State.Initial
    }

    @Test
    fun `on action gets triggered and moves to next state`() {
        val sm = StateMachine {
            inState<State.Initial> {
                on<Action.A1> { _, _, setState ->
                    setState { State.S1 }
                }
            }

            inState<State.S1> {
                on<Action.A2> { _, _, setState ->
                    setState { State.S2 }
                }

            }

        }
        val state = sm.state.testOverTime()

        state shouldEmitNext State.Initial

        sm.dispatchAsync(Action.A1)
        state shouldEmitNext State.S1

        sm.dispatchAsync(Action.A2)
        state shouldEmitNext State.S2
    }

    @Test
    fun `collectWhileInState stops after having moved to next state`() {

        val recordedValues = mutableListOf<Int>()
        val sm = StateMachine {
            inState<State.Initial> {
                collectWhileInState(flow {
                    emit(1)
                    delay(10)
                    emit(2)
                    delay(10)
                    emit(3)
                }) { v, _, setState ->
                    recordedValues.add(v)
                    if (v == 2)
                        setState { State.S1 }
                }
            }
        }
        val state = sm.state.testOverTime()

        state.shouldEmitNext(
            State.Initial,
            State.S1
        )

        state.shouldNotEmitMoreValues()
        Assert.assertEquals(listOf(1, 2), recordedValues) // 3 is not emitted
    }

    @Test
    fun `move from collectWhileInState to next state with action`() {

        val sm = StateMachine {
            inState<State.Initial> {
                collectWhileInState(flowOf(1)) { _, _, setState ->
                    setState { State.S1 }
                }
            }

            inState<State.S1> {
                on<Action.A1> { _, _, setState ->
                    setState { State.S2 }
                }
            }

            inState<State.S2> {
                on<Action.A2> { _, _, setState ->
                    setState { State.S1 }

                }
            }
        }

        val state = sm.state.testOverTime()

        state.shouldEmitNext(
            State.Initial,
            State.S1
        )

        sm.dispatchAsync(Action.A1)
        state shouldEmitNext State.S2

        sm.dispatchAsync(Action.A2)
        state shouldEmitNext State.S1

        sm.dispatchAsync(Action.A1)
        state shouldEmitNext State.S2

        sm.dispatchAsync(Action.A2)
        state shouldEmitNext State.S1
    }

    @Test
    fun `onEnter in a state triggers but doesnt stop execution on leaving state`() {
        val order = ArrayList<Int>()
        val sm = StateMachine {
            inState<State.Initial> {
                onEnter { _, setState ->
                    order.add(0)
                    setState { State.S1 }
                    delay(50)
                    order.add(1)
                }
            }

            inState<State.S1> {
                onEnter { _, setState ->
                    order.add(2)
                    delay(100)
                    setState { State.S2 }
                    order.add(3)
                }
            }

            inState<State.S2> {
                onEnter { _, _ ->
                    order.add(4)
                }

                on<Action.A1> { _, _, setState ->
                    setState { State.S2 }
                }
            }
        }

        val state = sm.state.testOverTime()

        state.shouldEmitNext(State.Initial, State.S1, State.S2)

        sm.dispatchAsync(Action.A1)
        state.shouldNotHaveEmittedSinceLastCheck(timeout20Milliseconds)

        Assert.assertEquals(listOf(0, 2, 1, 3, 4), order)
    }

    @Test
    fun `on entering the same state doesnt tringer onEnter again`() {
        var s1Entered = 0
        val sm = StateMachine {
            inState<State.Initial> {
                onEnter { _, setState -> setState { State.S1 } }
            }

            inState<State.S1> {
                onEnter { _, _ -> s1Entered++ }
                on<Action.A1> { _, _, setState -> setState { State.S1 } }
            }
        }

        val state = sm.state.testOverTime()
        state.shouldEmitNext(State.Initial, State.S1)

        repeat(2) {
            sm.dispatchAsync(Action.A1) // Causes state transition to S1 again which is already current
            state.shouldNotHaveEmittedSinceLastCheck(timeout20Milliseconds)
            Assert.assertEquals(1, s1Entered)
        }
    }

    /*
    @Test
    fun `on Action triggers in state setState executes while being in same state`() {
        var setStateCalled = 0
        val sm = StateMachine {
            inState<State.Initial> {
                on<Action.A1> { _, _, setState ->
                    setState { setStateCalled++; it }
                    setState { setStateCalled++; it }
                }
            }
        }

        val state = sm.state.testOverTime()
        sm.dispatchAsync(Action.A1)
        state shouldEmitNext State.Initial

        Assert.assertEquals(2, setStateCalled)
    }
     */

    @Test
    fun `on Action changes state than second setState doesn't trigger anymore`() {
        var setStateCalled = 0
        val sm = StateMachine {
            inState<State.Initial> {
                on<Action.A1> { _, _, setState ->
                    setState { setStateCalled++; State.S1 }
                    setState { setStateCalled++; State.S2 }
                }
            }
            inState<State.S1> {
                onEnter { _, setState ->
                    delay(100)
                    setState { State.S3 }
                }
            }
        }

        val state = sm.state.testOverTime()
        sm.dispatchAsync(Action.A1)
        state shouldEmitNext State.Initial
        state shouldEmitNext State.S1
        state.shouldEmitNext(State.S3)
        Assert.assertEquals(1, setStateCalled)
    }

    @Test
    fun `setState with runIf returning false doesnt change state`() {
        var setS1Called = false
        var a1Dispatched = false
        val sm = StateMachine {
            inState<State.Initial> {
                on<Action.A1> { _, _, setState ->
                    a1Dispatched = true
                    setState(runIf = { false }) { setS1Called = true; State.S1 }
                }

                on<Action.A2> { _, _, setState ->
                    delay(100) // ensure that A1 setState{ } would have time be executed
                    setState { State.S2 }
                }
            }
        }

        val state = sm.state.testOverTime()
        state shouldEmitNext State.Initial

        sm.dispatchAsync(Action.A1)
        sm.dispatchAsync(Action.A2)

        state shouldEmitNext State.S2
        Assert.assertFalse(setS1Called)
        Assert.assertTrue(a1Dispatched)
    }
}
