package com.superwall.sdk.misc.primitives

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Holds state and provides synchronous updates + async action dispatching.
 *
 * [update] uses [MutableStateFlow.update] internally (CAS retry) —
 * concurrent updates from multiple actions are safe.
 *
 * Dispatch modes:
 * - [action]: fire-and-forget — launches in the actor's scope.
 * - [actionAndAwait]: dispatch + suspend until state matches a condition.
 *
 * ## Interceptors
 *
 * ```kotlin
 * actor.onUpdate { reducer, next ->
 *     next(reducer)  // call to proceed, skip to suppress
 * }
 *
 * actor.onAction { action, next ->
 *     next()  // call to proceed, skip to suppress
 * }
 * ```
 */
class Actor<S>(
    initial: S,
    internal val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    // -- Interceptor chains --------------------------------------------------

    private var updateChain: (Reducer<S>) -> Unit = { reducer ->
        _state.update { reducer.reduce(it) }
    }

    private var actionInterceptors: List<(action: Any, next: () -> Unit) -> Unit> = emptyList()

    /**
     * Add an update interceptor. Call `next(reducer)` to proceed,
     * or skip to suppress the update.
     */
    fun onUpdate(interceptor: (reducer: Reducer<S>, next: (Reducer<S>) -> Unit) -> Unit) {
        val previous = updateChain
        updateChain = { reducer -> interceptor(reducer, previous) }
    }

    /**
     * Add an action interceptor. Call `next()` to proceed,
     * or skip to suppress the action. Action is [Any] — cast to inspect.
     */
    fun onAction(interceptor: (action: Any, next: () -> Unit) -> Unit) {
        actionInterceptors = actionInterceptors + interceptor
    }

    /** Atomic state mutation using CAS retry, routed through update interceptors. */
    fun update(reducer: Reducer<S>) {
        updateChain(reducer)
    }

    /** Fire-and-forget: launch action in actor's scope, routed through interceptors. */
    fun <Ctx> action(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        val execute = {
            scope.launch { action.execute.invoke(ctx) }
            Unit
        }
        runInterceptorChain(action, execute)
    }

    /**
     * Dispatch action and suspend until state matches [until].
     *
     * Actor-native awaiting: fire the action, observe the state transition.
     */
    suspend fun <Ctx> actionAndAwait(
        ctx: Ctx,
        action: TypedAction<Ctx>,
        until: (S) -> Boolean,
    ): S {
        action(ctx, action)
        return state.first { until(it) }
    }

    /**
     * Dispatch action inline and suspend until it completes.
     * Goes through action interceptors. Use for cross-slice coordination
     * where the caller needs to await the action finishing.
     */
    suspend fun <Ctx> dispatchAwait(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        var shouldExecute = true
        if (actionInterceptors.isNotEmpty()) {
            shouldExecute = false
            var chain: () -> Unit = { shouldExecute = true }
            for (i in actionInterceptors.indices.reversed()) {
                val interceptor = actionInterceptors[i]
                val next = chain
                chain = { interceptor(action, next) }
            }
            chain()
        }
        if (shouldExecute) {
            action.execute.invoke(ctx)
        }
    }

    /**
     * Create a scoped projection of this actor onto a sub-state.
     *
     * The returned [ScopedState] reads/writes only the sub-state,
     * automatically lifting reducers and mapping state.
     */
    fun <Sub> scoped(
        get: (S) -> Sub,
        set: (S, Sub) -> S,
    ): ScopedState<S, Sub> = ScopedState(this, get, set)

    private fun runInterceptorChain(
        action: Any,
        terminal: () -> Unit,
    ) {
        if (actionInterceptors.isEmpty()) {
            terminal()
        } else {
            var chain: () -> Unit = terminal
            for (i in actionInterceptors.indices.reversed()) {
                val interceptor = actionInterceptors[i]
                val next = chain
                chain = { interceptor(action, next) }
            }
            chain()
        }
    }
}

/**
 * Common interface for reading, updating, and dispatching on state.
 *
 * Both [Actor] (root) and [ScopedState] (projection) implement this.
 * Contexts depend on [StateActor] — they never see the concrete type.
 */
interface StateActor<S> {
    val state: StateFlow<S>

    /** Atomic state mutation. */
    fun update(reducer: Reducer<S>)

    /** Fire-and-forget action dispatch. */
    fun <Ctx> dispatch(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    )

    /** Dispatch action inline, suspending until it completes. */
    suspend fun <Ctx> dispatchAwait(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    )

    /** Dispatch action, suspending until state matches [until]. */
    suspend fun <Ctx> dispatchAndAwait(
        ctx: Ctx,
        action: TypedAction<Ctx>,
        until: (S) -> Boolean,
    ): S
}

/**
 * Wraps an [Actor] as a [StateActor] — useful for standalone actors
 * that aren't part of a composite root (e.g. product cache).
 */
fun <S> Actor<S>.asStateActor(): StateActor<S> =
    object : StateActor<S> {
        override val state = this@asStateActor.state

        override fun update(reducer: Reducer<S>) = this@asStateActor.update(reducer)

        override fun <Ctx> dispatch(
            ctx: Ctx,
            action: TypedAction<Ctx>,
        ) = this@asStateActor.action(ctx, action)

        override suspend fun <Ctx> dispatchAwait(
            ctx: Ctx,
            action: TypedAction<Ctx>,
        ) = this@asStateActor.dispatchAwait(ctx, action)

        override suspend fun <Ctx> dispatchAndAwait(
            ctx: Ctx,
            action: TypedAction<Ctx>,
            until: (S) -> Boolean,
        ) = this@asStateActor.actionAndAwait(ctx, action, until)
    }

/**
 * A scoped projection of an [Actor] onto a sub-state.
 *
 * Domain actions see only their state — they call [update] with
 * `Reducer<Sub>` and read [state] as `StateFlow<Sub>`. The lifting
 * to the root state is automatic and invisible to the action.
 *
 * ```kotlin
 * val identity = sdkActor.scoped(
 *     get = { it.identity },
 *     set = { root, sub -> root.copy(identity = sub) },
 * )
 *
 * // Inside identity actions:
 * actor.update(IdentityState.Updates.Identify("user"))  // just works
 * actor.state.value.aliasId  // reads IdentityState, not SdkState
 * ```
 */
class ScopedState<Root, Sub>(
    private val root: Actor<Root>,
    private val get: (Root) -> Sub,
    private val set: (Root, Sub) -> Root,
) : StateActor<Sub> {
    /** Projected state — only the sub-state. */
    override val state: StateFlow<Sub> by lazy {
        val initial = get(root.state.value)
        val derived = MutableStateFlow(initial)
        root.scope.launch {
            root.state.collect { derived.value = get(it) }
        }
        derived.asStateFlow()
    }

    /** Update only the sub-state. Automatically lifts to root. */
    override fun update(reducer: Reducer<Sub>) {
        root.update(
            object : Reducer<Root> {
                override val reduce: (Root) -> Root = { rootState ->
                    set(rootState, reducer.reduce(get(rootState)))
                }
            },
        )
    }

    /** Fire-and-forget action dispatch in the root actor's scope. */
    override fun <Ctx> dispatch(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        root.action(ctx, action)
    }

    /**
     * Dispatch action inline and suspend until it completes.
     * Goes through the root actor's interceptors.
     */
    override suspend fun <Ctx> dispatchAwait(
        ctx: Ctx,
        action: TypedAction<Ctx>,
    ) {
        root.dispatchAwait(ctx, action)
    }

    /** Dispatch action and suspend until the sub-state matches [until]. */
    override suspend fun <Ctx> dispatchAndAwait(
        ctx: Ctx,
        action: TypedAction<Ctx>,
        until: (Sub) -> Boolean,
    ): Sub {
        root.action(ctx, action)
        return state.first { until(it) }
    }
}
