# Superwall SDK — Actor/Event Loop Architecture Sketch

## Foundation: The Engine and Types

### State

```kotlin
// The unified state — domains own slices
data class SdkState(
    val identity: IdentityState = IdentityState(),
    val configReady: Boolean = false,
) {
    companion object {
        fun initial() = SdkState()
    }

    // Self-reducing events at the SdkState level.
    // Every event the engine processes is one of these.
    internal sealed class Updates(
        override val applyOn: Fx.(SdkState) -> SdkState
    ) : Reducer<SdkState>(applyOn) {

        // Lifts an identity update to operate on the identity slice of SdkState
        data class UpdateIdentity(val update: IdentityState.Updates) : Updates({
            it.copy(identity = update.applyOn(this, it.identity))
        })

        // Cross-cutting: resets config readiness when switching users
        object FullResetOnIdentify : Updates({
            it.copy(configReady = false)
        })

        // Dispatched by ConfigManager when config is first retrieved
        object ConfigReady : Updates({
            it.copy(configReady = true)
        })
    }
}

data class IdentityState(
    val appUserId: String? = null,
    val aliasId: String = IdentityLogic.generateAlias(),
    val seed: Int = IdentityLogic.generateSeed(),
    val userAttributes: Map<String, Any> = emptyMap(),
    val pending: Set<Pending> = emptySet(),
    val isReady: Boolean = false,
    val appInstalledAtString: String = "",  // set once, never changes
) {
    val userId: String get() = appUserId ?: aliasId
    val isLoggedIn: Boolean get() = appUserId != null
    val enrichedAttributes: Map<String, Any>
        get() = userAttributes.toMutableMap().apply {
            put(Keys.APP_USER_ID, userId)
            put(Keys.ALIAS_ID, aliasId)
        }

    fun resolve(item: Pending): IdentityState {
        val next = pending - item
        return if (next.isEmpty()) copy(pending = next, isReady = true) else copy(pending = next)
    }

    // Self-reducing events — each carries its own reduce logic
    internal sealed class Updates(
        override val applyOn: Fx.(IdentityState) -> IdentityState
    ) : Reducer<IdentityState>(applyOn) {

        data class Identify(val userId: String, val options: IdentityOptions?) : Updates({ state -> ... })
        data class SeedResolved(val seed: Int) : Updates({ state -> ... })
        object SeedSkipped : Updates({ state -> state.resolve(Pending.Seed) })
        data class AttributesMerged(val attrs: Map<String, Any>, ...) : Updates({ state -> ... })
        object AssignmentsCompleted : Updates({ state -> state.resolve(Pending.Assignments) })
        data class Configure(val neverCalledStaticConfig: Boolean, val isFirstAppOpen: Boolean) : Updates({ state -> ... })
        object Ready : Updates({ state -> state.copy(isReady = true) })
        object Reset : Updates({ state -> ... })
    }
}

enum class Pending { Seed, Assignments }

internal object Keys {
    const val APP_USER_ID = "appUserId"
    const val ALIAS_ID = "aliasId"
    const val SEED = "seed"
}
```

**Key design choice**: `appInstalledAtString` is stored on `IdentityState` itself. All reduce
lambdas read it from `state.appInstalledAtString` instead of receiving it as a constructor param
on every event. It's set once in `createInitialIdentityState()` and preserved through resets.

### Self-Reducing Events

Events carry their own reduce logic. No separate reducer function needed — the engine
just calls `event.applyOn(fx, state)`.

```kotlin
// Base class: any event that knows how to reduce a state slice
internal open class Reducer<S>(open val applyOn: Fx.(S) -> S) : SdkEvent

// Domain events extend Reducer with their slice type
// SdkState.Updates extends Reducer<SdkState> — top-level routing
// IdentityState.Updates extends Reducer<IdentityState> — identity logic

// SdkState.Updates.UpdateIdentity bridges the two:
// it wraps an IdentityState.Updates and applies it to the identity slice
data class UpdateIdentity(val update: IdentityState.Updates) : Updates({
    it.copy(identity = update.applyOn(this, it.identity))
})
```

**No rootReduce()**: The engine casts every event to `Reducer<SdkState>` and calls `applyOn`
directly. `SdkState.Updates` is the only event type the engine sees. Domain events are always
wrapped (e.g. identity events go through `UpdateIdentity`).

### Self-Executing Effects

Domain effects carry their own execution logic via a receiver lambda. The effect runner
only needs one `is` check per domain — the irreducible minimum due to type erasure.

```kotlin
// Base: IdentityEffectDeps is the receiver, giving access to all deps
internal sealed class IdentityEffect(
    val execute: suspend IdentityEffectDeps.(dispatch: (SdkEvent) -> Unit) -> Unit
) : Effect {
    data class ResolveSeed(val userId: String) : IdentityEffect({ dispatch ->
        val config = configProvider()
        if (config?.featureFlags?.enableUserIdSeed == true) {
            userId.sha256MappedToRange()?.let {
                dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.SeedResolved(it)))
            } ?: dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.SeedSkipped))
        } else {
            dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.SeedSkipped))
        }
    })

    object FetchAssignments : IdentityEffect({ dispatch ->
        try { fetchAssignments?.invoke() }
        finally { dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.AssignmentsCompleted)) }
    })

    // ... other effects carry their logic the same way
}

// Deps interface — used as receiver in effect lambdas
internal interface IdentityEffectDeps {
    val configProvider: () -> Config?
    val webPaywallRedeemer: (() -> WebPaywallRedeemer)?
    val testModeManager: TestModeManager?
    val deviceHelper: DeviceHelper
    val delegate: (() -> SuperwallDelegateAdapter)?
    val completeReset: () -> Unit
    val fetchAssignments: (suspend () -> Unit)?
    val notifyUserChange: ((Map<String, Any>) -> Unit)?
}
```

### Effects

Shared effects are handled by the engine or effect runner directly.
Domain effects are self-executing.

```kotlin
interface Effect {
    data class Persist(val storable: Storable<*>, val value: Any) : Effect
    data class Delete(val storable: Storable<*>) : Effect
    data class Track(val event: Trackable) : Effect
    data class Dispatch(val event: SdkEvent) : Effect   // handled by engine inline
    data class Deferred(                                 // held by engine until predicate matches
        val until: (SdkState) -> Boolean,
        val effects: List<Effect>,
    ) : Effect
}
```

### The Effects Scope (DSL)

The reducer receives an `Fx` receiver. High-frequency effects get convenience methods.
Domain effects use `effect { }`. Deferred effects use `defer(until = { ... }) { }`.

```kotlin
class Fx {
    internal val pending = mutableListOf<Effect>()

    fun <T : Any> persist(storable: Storable<T>, value: T) { pending += Effect.Persist(storable, value) }
    fun delete(storable: Storable<*>) { pending += Effect.Delete(storable) }
    fun track(event: Trackable) { pending += Effect.Track(event) }
    fun dispatch(event: SdkEvent) { pending += Effect.Dispatch(event) }
    fun effect(which: () -> Effect) { pending += which() }
    fun log(logLevel: LogLevel, scope: LogScope, message: String, ...) { Logger.debug(...) }

    // Deferred effects — wait for a state predicate before executing
    fun defer(until: (SdkState) -> Boolean, block: DeferScope.() -> Unit) {
        val scope = DeferScope()
        scope.block()
        pending += Effect.Deferred(until, scope.effects)
    }

    // Either integration — branch state + effects based on result
    fun <T, S> fold(
        either: Either<T, Throwable>,
        onSuccess: Fx.(T) -> S,
        onFailure: Fx.(Throwable) -> S,
    ): S = when (either) {
        is Either.Success -> onSuccess(either.value)
        is Either.Failure -> onFailure(either.error)
    }
}
```

Usage in self-reducing events:

```kotlin
object Reset : Updates({ state ->
    val fresh = IdentityState(appInstalledAtString = state.appInstalledAtString)
    persist(AliasId, fresh.aliasId)
    persist(Seed, fresh.seed)
    delete(AppUserId)
    delete(UserAttributes)

    val merged = IdentityLogic.mergeAttributes(
        newAttributes = mapOf(Keys.ALIAS_ID to fresh.aliasId, Keys.SEED to fresh.seed),
        oldAttributes = emptyMap(),
        appInstalledAtString = state.appInstalledAtString,
    )
    persist(UserAttributes, merged)
    fresh.copy(userAttributes = merged, isReady = true)
})
```

### The Engine

Single loop. Single channel. Single state. No external reducer — events are self-reducing.

```kotlin
internal class Engine(
    initial: SdkState,
    private val runEffect: suspend (Effect, dispatch: (SdkEvent) -> Unit) -> Unit,
    scope: CoroutineScope,
) {
    private val events = Channel<SdkEvent>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<SdkState> = _state.asStateFlow()
    private val deferred = mutableListOf<Effect.Deferred>()

    fun dispatch(event: SdkEvent) { events.trySend(event) }

    init {
        scope.launch {
            for (event in events) {
                // 1. Reduce — self-reducing event applies itself
                val fx = Fx()
                val next = withErrorTracking {
                    (event as Reducer<SdkState>).applyOn(fx, _state.value)
                }.let { either ->
                    when (either) {
                        is Success -> either.value
                        is Failure -> _state.value
                    }
                }
                _state.value = next

                // 2. Process effects
                for (effect in fx.pending) {
                    when (effect) {
                        is Effect.Dispatch -> dispatch(effect.event)
                        is Effect.Deferred -> deferred += effect
                        else -> launch { withErrorTracking { runEffect(effect, ::dispatch) } }
                    }
                }

                // 3. Check deferred batches against new state
                if (deferred.isNotEmpty()) {
                    val ready = deferred.filter { it.until(next) }
                    if (ready.isNotEmpty()) {
                        deferred.removeAll(ready.toSet())
                        for (batch in ready) {
                            for (effect in batch.effects) {
                                when (effect) {
                                    is Effect.Dispatch -> dispatch(effect.event)
                                    else -> launch { withErrorTracking { runEffect(effect, ::dispatch) } }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
```

### The Effect Runner

Creates `IdentityEffectDeps` once. Routes shared effects inline.
Domain effects are self-executing — one `is` check per domain.

```kotlin
internal fun createEffectRunner(
    storage: Storage,
    track: suspend (Trackable) -> Unit,
    configProvider: () -> Config?,
    // ... other deps
): suspend (Effect, (SdkEvent) -> Unit) -> Unit {
    val identityDeps = object : IdentityEffectDeps {
        override val configProvider = configProvider
        // ... wire all deps once
    }

    return { effect, dispatch ->
        when (effect) {
            is Effect.Persist -> writeAny(storage, effect.storable, effect.value)
            is Effect.Delete -> deleteAny(storage, effect.storable)
            is Effect.Track -> track(effect.event)
            is IdentityEffect -> effect.execute(identityDeps, dispatch)
            // Future domains: is ConfigEffect -> effect.execute(configDeps, dispatch)
        }
    }
}
```

### Testing

Self-reducing events are pure functions — test without mocks, coroutines, or flakiness:

```kotlin
@Test fun `identify sets userId and persists`() {
    val fx = Fx()
    val initial = IdentityState(appInstalledAtString = "2024-01-01")

    val next = IdentityState.Updates.Identify("user_123", null).applyOn(fx, initial)

    assertEquals("user_123", next.appUserId)
    assertTrue(fx.pending.any { it is Effect.Persist && it.storable == AppUserId })
}
```

For integration tests, the `IdentityManager` facade dispatches events and exposes state:

```kotlin
@Test fun `configure calls getAssignments when logged in`() = runTest {
    val manager = createManagerWithScope(testScope, existingAppUserId = "user-123", neverCalledStaticConfig = true)

    manager.configure()
    Thread.sleep(100)
    manager.engine.dispatch(SdkState.Updates.ConfigReady)
    Thread.sleep(100)

    coVerify(exactly = 1) { configManager.getAssignments() }
}
```

---

## Event Flow Diagram

```
IdentityManager.identify("user_123")
    ↓
engine.dispatch(SdkState.Updates.UpdateIdentity(IdentityState.Updates.Identify("user_123", null)))
    ↓
Engine event loop picks up event
    ↓
(event as Reducer<SdkState>).applyOn(fx, state)
    ↓
UpdateIdentity.applyOn runs: state.copy(identity = update.applyOn(fx, state.identity))
    ↓
Identify.applyOn runs: sanitize userId, persist, track, defer effects, return new IdentityState
    ↓
Engine updates _state, processes fx.pending effects:
  - Effect.Persist(AppUserId, "user_123")     → launched: storage.write(AppUserId, ...)
  - Effect.Track(IdentityAlias)               → launched: track(...)
  - Effect.Deferred(until = { configReady })  → held by engine, checked after each transition
  - IdentityEffect.CheckWebEntitlements       → launched: effect.execute(identityDeps, dispatch)
    ↓
Later: ConfigManager dispatches SdkState.Updates.ConfigReady
    ↓
Engine processes ConfigReady, checks deferred → predicate matches → launches:
  - IdentityEffect.ResolveSeed("user_123")
  - IdentityEffect.FetchAssignments
  - IdentityEffect.ReevaluateTestMode(...)
    ↓
ResolveSeed completes → dispatches SdkState.Updates.UpdateIdentity(SeedResolved(42))
    ↓
Engine reduces → identity.resolve(Pending.Seed) → if no more pending, isReady = true
```

---

## The Facade (IdentityManager)

The facade's public API is unchanged. Internally it wraps every dispatch in `UpdateIdentity`:

```kotlin
class IdentityManager(...) {
    internal val engine: Engine

    private fun dispatchIdentity(update: IdentityState.Updates) {
        engine.dispatch(SdkState.Updates.UpdateIdentity(update))
    }

    fun configure() { dispatchIdentity(IdentityState.Updates.Configure(...)) }
    fun identify(userId: String, options: IdentityOptions? = null) { dispatchIdentity(IdentityState.Updates.Identify(userId, options)) }
    fun reset(duringIdentify: Boolean) { if (!duringIdentify) dispatchIdentity(IdentityState.Updates.Reset) }
    fun mergeUserAttributes(attrs: Map<String, Any?>, shouldTrackMerge: Boolean = true) { dispatchIdentity(IdentityState.Updates.AttributesMerged(...)) }

    // State reads — just read the StateFlow
    private val identity get() = engine.state.value.identity
    val appUserId: String? get() = identity.appUserId
    val aliasId: String get() = identity.aliasId
    val userId: String get() = identity.userId
    val isLoggedIn: Boolean get() = identity.isLoggedIn
    val hasIdentity: Flow<Boolean> get() = engine.state.map { it.identity.isReady }.filter { it }
}
```

---

## What Changed vs Original Sketch

| Original Sketch | Current Implementation |
|----------------|----------------------|
| `sealed interface SdkEvent` with nested event hierarchies | `interface SdkEvent` as marker; events are self-reducing `Reducer<S>` subclasses |
| `fun interface Reduce<S, E>` — external reducer function | `Reducer<S>(val applyOn: Fx.(S) -> S)` — events carry their own reduce logic |
| `rootReduce` function routing events to slice reducers | `SdkState.Updates` sealed class; `UpdateIdentity` wraps identity events |
| `identityReduce` matching on event types in a `when` block | Each `IdentityState.Updates` subclass carries its reduce lambda |
| `appInstalledAtString` passed to every event | Stored on `IdentityState`, read from `state.appInstalledAtString` |
| `pendingSeed: Boolean` + `pendingAssignments: Boolean` | `pending: Set<Pending>` with `enum class Pending { Seed, Assignments }` |
| String literals `"appUserId"`, `"aliasId"`, `"seed"` | `Keys.APP_USER_ID`, `Keys.ALIAS_ID`, `Keys.SEED` |
| Separate `runIdentityEffect` function with `when` matching | Self-executing effects: `IdentityEffect(val execute: suspend IdentityEffectDeps.(...) -> Unit)` |
| Engine takes `reduce: Reduce<SdkState, SdkEvent>` param | Engine casts event to `Reducer<SdkState>` directly — no external reducer |
| Effects wait by suspending (`awaitFirstValidConfig`) | `defer(until = { it.configReady }) { ... }` — declarative, non-blocking |

---

## Adding a New Domain

Each domain follows the same pattern:
- **State**: data class with nested `Updates` sealed class extending `Reducer<SliceState>`
- **Effects**: sealed class with self-executing lambdas on a deps interface
- **Facade**: wraps dispatches, exposes state reads
- **Wiring**: one `SdkState.Updates` variant + one `is` match in the effect runner

---

## Config Domain (Phase 3 — In Progress)

### Architecture

Config state is managed via `ConfigSlice` (state + self-reducing `Updates`) and
`ConfigEffect` (self-executing effects on `ConfigEffectDeps`).

**Current approach: Adapter pattern**

`ConfigManager` keeps its existing constructor and public API surface for backward
compatibility with tests and consumers. Internally, it dispatches state updates to the
engine via `SdkState.Updates.UpdateConfig(...)` when `engineRef` is set. This is the
"write-through" adapter — local state is updated as before AND engine state is kept in sync.

**Files:**
- `ConfigSlice.kt` — `ConfigSlice` data class + `Updates` sealed class (Phase, triggersByEventName, unconfirmedAssignments)
- `ConfigEffects.kt` — `ConfigEffect` sealed class + `ConfigEffectDeps` interface
- `ConfigManager.kt` — Adapter facade: same public API, dispatches to engine when available
- `SdkState.kt` — Has `config: ConfigSlice` field + `UpdateConfig` variant in `Updates`
- `EffectRunner.kt` — Routes `ConfigEffect` through `ConfigEffectDeps`
- `DependencyContainer.kt` — Wires `configManager.engineRef = { identityManager.engine }`

**ConfigSlice.Phase mapping to ConfigState:**
| `ConfigSlice.Phase` | `ConfigState` (legacy) |
|---------------------|----------------------|
| `None` | `ConfigState.None` |
| `Retrieving` | `ConfigState.Retrieving` |
| `Retrying` | `ConfigState.Retrying` |
| `Retrieved(config)` | `ConfigState.Retrieved(config)` |
| `Failed(error)` | `ConfigState.Failed(error)` |

**Next steps for full migration:**
1. Move `ConfigManager.fetchConfig()` logic into `ConfigEffect.FetchConfig` execution
2. Replace `configState: MutableStateFlow<ConfigState>` with engine state reads
3. Replace `Assignments` class with `ConfigSlice.Updates` dispatches
4. Migrate consumers to read from `engine.state.config` instead of `configManager`

---

## What Disappears

| Deleted | Replaced By |
|---------|-------------|
| `rootReduce()` function | `SdkState.Updates` sealed class — events route themselves |
| `identityReduce()` function | Self-reducing events in `IdentityState.Updates` |
| `runIdentityEffect()` function | Self-executing `IdentityEffect` subclasses |
| `IdentityManager` (342 lines, old) | `IdentityManager` facade (~50 lines) + self-reducing events + self-executing effects |
| `runBlocking` on every property read | `engine.state.value.identity.*` — no blocking |
| `CopyOnWriteArrayList<Job>` | `pending: Set<Pending>` + `isReady` flag in state |
| `identityFlow` MutableStateFlow | `state.map { it.identity.isReady }` |
| Circular `Superwall.instance.reset()` dep | `dispatch(SdkState.Updates.FullResetOnIdentify)` — no circular dependency |

---

## Migration Order

1. **Phase 1: Identity** ✅ — Complete. Self-reducing events, self-executing effects, deferred effects, facade.

2. **Phase 2: Entitlements** — Replaces 4 mutable sets + `ConcurrentHashMap` +
   manual sync. Same pattern: `EntitlementState.Updates`, `EntitlementEffect`, `SdkState.Updates.UpdateEntitlements`.

3. **Phase 3: Config** 🔄 — In progress. `ConfigSlice` + `ConfigEffect` created,
   adapter pattern wired. ConfigManager dispatches to engine alongside local state updates.
   Next: move imperative logic into effects, remove local `MutableStateFlow`.

4. **Phase 4: Wire presentation pipeline to consume engine state** — Don't rewrite the
   pipeline, just change its data sources from managers to `engine.state`.

5. **Phase 5: Interceptors** — Higher-order functions on reducers for analytics, logging, privacy.

---

## Threading Model

The single loop only serializes **state transitions** (pure reducers). Effects run with full
coroutine flexibility.

```
Event arrives
    ↓
Reducer runs (pure, fast, single-threaded dispatcher)
    ↓ returns new state + list of Effects
State updated atomically
    ↓
Each effect launches in its own coroutine (concurrent, any dispatcher)
    ↓ results dispatch new events back into the loop
```

Reducers never think about threading — they're pure data in, data out.
Effect runners own their threading via standard coroutine primitives.
The loop never blocks waiting for effects to complete.
