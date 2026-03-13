# Superwall SDK — Actor Architecture Sketch

## Foundation: Primitives

### Actor + StateActor + ScopedState

A single `Actor<SdkState>` holds the truth for the entire SDK. Domain facades
never see `SdkState` — they operate on `ScopedState` projections that implement
the `StateActor<S>` interface.

```kotlin
// Root actor — single source of truth
class Actor<S>(initial: S, scope: CoroutineScope) {
    val state: StateFlow<S>

    fun update(reducer: Reducer<S>)                           // synchronous CAS, through interceptors
    fun <Ctx> action(ctx: Ctx, action: TypedAction<Ctx>)      // fire-and-forget in actor scope
    suspend fun <Ctx> dispatchAwait(ctx: Ctx, action: TypedAction<Ctx>)  // inline await
    suspend fun <Ctx> actionAndAwait(ctx: Ctx, action: TypedAction<Ctx>, until: (S) -> Boolean): S

    fun onUpdate(interceptor: ...)   // intercept state updates (debugging, logging)
    fun onAction(interceptor: ...)   // intercept action dispatch
}

// Common interface — both Actor and ScopedState implement this.
// Contexts depend on StateActor, never the concrete type.
interface StateActor<S> {
    val state: StateFlow<S>
    fun update(reducer: Reducer<S>)
    fun <Ctx> dispatch(ctx: Ctx, action: TypedAction<Ctx>)
    suspend fun <Ctx> dispatchAwait(ctx: Ctx, action: TypedAction<Ctx>)
    suspend fun <Ctx> dispatchAndAwait(ctx: Ctx, action: TypedAction<Ctx>, until: (S) -> Boolean): S
}

// Lens-based projection — domain actions see only their slice
class ScopedState<Root, Sub>(root: Actor<Root>, get: (Root) -> Sub, set: (Root, Sub) -> Root) : StateActor<Sub>
```

### Reducer + TypedAction

```kotlin
// Pure state transform — (S) -> S, no side effects
interface Reducer<S> {
    val reduce: (S) -> S
}

// Async work with typed context — actions run in the actor's scope
interface TypedAction<Ctx> {
    val execute: suspend Ctx.() -> Unit
}
```

### Context Hierarchy

F-bounded polymorphism ensures type-safe `effect()` dispatch.

```kotlin
// Pure actor primitive — state access + fire-and-forget dispatch
interface ActorContext<S, Self : ActorContext<S, Self>> {
    val actor: StateActor<S>
    val scope: CoroutineScope
    fun effect(action: TypedAction<Self>) { actor.dispatch(this as Self, action) }
}

// SDK layer — adds storage persistence helpers
interface SdkContext<S, Self : SdkContext<S, Self>> : ActorContext<S, Self> {
    val storage: Storage
    fun <T : Any> persist(storable: Storable<T>, value: T)
    fun delete(storable: Storable<*>)
}

// Domain contexts extend SdkContext with their deps
interface IdentityContext : SdkContext<IdentityState, IdentityContext> { ... }
interface ConfigContext : SdkContext<SdkConfigState, ConfigContext> { ... }
interface EntitlementsContext : SdkContext<EntitlementsState, EntitlementsContext> { ... }
```

---

## Root State

```kotlin
data class SdkState(
    val identity: IdentityState = IdentityState(),
    val config: SdkConfigState = SdkConfigState(),
    val entitlements: EntitlementsState = EntitlementsState(),
) {
    val isReady: Boolean get() = identity.isReady && config.isRetrieved
}

// Scoped projections — each domain gets its own lens
fun Actor<SdkState>.identityState(): ScopedState<SdkState, IdentityState> =
    scoped(get = { it.identity }, set = { root, sub -> root.copy(identity = sub) })
fun Actor<SdkState>.configState(): ScopedState<SdkState, SdkConfigState> =
    scoped(get = { it.config }, set = { root, sub -> root.copy(config = sub) })
fun Actor<SdkState>.entitlementsState(): ScopedState<SdkState, EntitlementsState> =
    scoped(get = { it.entitlements }, set = { root, sub -> root.copy(entitlements = sub) })
```

---

## Domain Pattern: State + Updates + Actions + Context + Facade

Each domain follows the same structure:

### 1. State — immutable data class

```kotlin
data class IdentityState(
    val appUserId: String? = null,
    val aliasId: String = IdentityLogic.generateAlias(),
    val seed: Int = IdentityLogic.generateSeed(),
    val userAttributes: Map<String, Any> = emptyMap(),
    val pending: Set<Pending> = emptySet(),
    val isReady: Boolean = false,
    val appInstalledAtString: String = "",
) {
    val userId: String get() = appUserId ?: aliasId
    val isLoggedIn: Boolean get() = appUserId != null
    // ... derived properties
```

### 2. Updates — pure reducers nested in state class

```kotlin
    internal sealed class Updates(
        override val reduce: (IdentityState) -> IdentityState,
    ) : Reducer<IdentityState> {

        data class Identify(val userId: String) : Updates({ state ->
            val sanitized = IdentityLogic.sanitize(userId)
            if (sanitized.isNullOrEmpty() || sanitized == state.appUserId) state
            else {
                val base = if (state.appUserId != null) IdentityState(appInstalledAtString = state.appInstalledAtString) else state
                base.copy(appUserId = sanitized, pending = setOf(Pending.Seed, Pending.Assignments), isReady = false)
            }
        })

        object SeedSkipped : Updates({ state -> state.resolve(Pending.Seed) })
        object AssignmentsCompleted : Updates({ state -> state.resolve(Pending.Assignments) })
        object Reset : Updates({ state -> IdentityState(appInstalledAtString = state.appInstalledAtString).copy(isReady = true) })
        // ...
    }
```

### 3. Actions — async work with full context access

```kotlin
    internal sealed class Actions(
        override val execute: suspend IdentityContext.() -> Unit,
    ) : TypedAction<IdentityContext> {

        data class Identify(val userId: String, val options: IdentityOptions?) : Actions({
            val sanitized = IdentityLogic.sanitize(userId)
            if (!sanitized.isNullOrEmpty() && sanitized != actor.state.value.appUserId) {
                if (actor.state.value.appUserId != null) completeReset()
                actor.update(Updates.Identify(sanitized))                  // synchronous state change
                persist(AppUserId, sanitized)                              // persist via SdkContext
                track(InternalSuperwallEvent.IdentityAlias())              // side effect
                effect(ResolveSeed(sanitized))                             // fire-and-forget sub-action
                effect(FetchAssignments)                                   // fire-and-forget sub-action
            }
        })

        object FetchAssignments : Actions({
            try { configState.dispatchAwait(configCtx, SdkConfigState.Actions.FetchAssignments) }
            finally { actor.update(Updates.AssignmentsCompleted) }
        })

        data class MergeAttributes(val attrs: Map<String, Any?>, ...) : Actions({
            actor.update(Updates.AttributesMerged(attrs))
            persist(UserAttributes, actor.state.value.userAttributes)
        })
        // ...
    }
}
```

### 4. Context — deps interface extending SdkContext

```kotlin
internal interface IdentityContext : SdkContext<IdentityState, IdentityContext> {
    val configProvider: () -> Config?
    val configManager: ConfigManager
    val configState: StateActor<SdkConfigState>
    val configCtx: ConfigContext get() = configManager     // cross-state dispatch
    val deviceHelper: DeviceHelper
    val completeReset: () -> Unit
    val track: suspend (Trackable) -> Unit
    val notifyUserChange: ((Map<String, Any>) -> Unit)?
    // ...
}
```

### 5. Facade — implements context, dispatches actions

```kotlin
class IdentityManager(
    override val deviceHelper: DeviceHelper,
    override val storage: Storage,
    override val configManager: ConfigManager,
    override val actor: StateActor<IdentityState>,       // scoped projection
    private val configActor: StateActor<SdkConfigState>,
    // ... all override params
) : IdentityContext {
    override val scope: CoroutineScope get() = ioScope
    override val configState: StateActor<SdkConfigState> get() = configActor

    // State reads — just read the StateFlow
    private val identity get() = actor.state.value
    val appUserId: String? get() = identity.appUserId
    val aliasId: String get() = identity.aliasId
    val isLoggedIn: Boolean get() = identity.isLoggedIn

    // Actions — dispatch with self as context
    fun configure() { actor.dispatch(this, IdentityState.Actions.Configure(...)) }
    fun identify(userId: String, options: IdentityOptions? = null) {
        actor.dispatch(this, IdentityState.Actions.Identify(userId, options))
    }
    fun reset(duringIdentify: Boolean) {
        if (!duringIdentify) actor.dispatch(this, IdentityState.Actions.Reset)
    }
}
```

### 6. Initial state — restore from storage before actor starts

```kotlin
internal fun createInitialIdentityState(storage: Storage, appInstalledAtString: String): IdentityState {
    val aliasId = storage.read(AliasId) ?: IdentityLogic.generateAlias().also { storage.write(AliasId, it) }
    val seed = storage.read(Seed) ?: IdentityLogic.generateSeed().also { storage.write(Seed, it) }
    return IdentityState(appUserId = storage.read(AppUserId), aliasId = aliasId, seed = seed, ...)
}
```

---

## Entitlements Domain

Simpler pattern — no async actions needed. State mutations are synchronous
(`actor.update`) with persistence via `persist()` on the facade (which IS the context).

```kotlin
data class EntitlementsState(
    val status: SubscriptionStatus = SubscriptionStatus.Unknown,
    val entitlementsByProduct: Map<String, Set<Entitlement>> = emptyMap(),
    val activeDeviceEntitlements: Set<Entitlement> = emptySet(),
    val backingActive: Set<Entitlement> = emptySet(),
    val allTracked: Set<Entitlement> = emptySet(),
) {
    // Derived properties
    val all: Set<Entitlement> get() = allTracked + entitlementsByProduct.values.flatten()
    val active: Set<Entitlement> get() = mergeEntitlementsPrioritized((backingActive + activeDeviceEntitlements).toList()).toSet()

    // Pure reducers
    internal sealed class Updates(override val reduce: ...) : Reducer<EntitlementsState> {
        data class SetActive(val entitlements: Set<Entitlement>) : Updates({ state -> ... })
        object SetInactive : Updates({ state -> ... })
        object SetUnknown : Updates({ state -> ... })
        data class AddProductEntitlements(val map: Map<String, Set<Entitlement>>) : Updates({ state -> ... })
        data class SetDeviceEntitlements(val entitlements: Set<Entitlement>) : Updates({ state -> ... })
    }

    // Actions exist but currently empty — available for future async work
    internal sealed class Actions(...) : TypedAction<EntitlementsContext>
}

// Facade — synchronous mutations + inline persistence
class Entitlements(
    override val storage: Storage,
    override val actor: StateActor<EntitlementsState>,
    actorScope: CoroutineScope,
) : EntitlementsContext {

    fun setSubscriptionStatus(value: SubscriptionStatus) {
        // Synchronous state update (CAS through interceptors)
        when (value) {
            is SubscriptionStatus.Active -> actor.update(Updates.SetActive(value.entitlements.toSet()))
            is SubscriptionStatus.Inactive -> actor.update(Updates.SetInactive)
            is SubscriptionStatus.Unknown -> actor.update(Updates.SetUnknown)
        }
        // Persist via SdkContext (calls storage.write under the hood)
        persist(StoredSubscriptionStatus, snapshot.status)
    }

    // All reads go through the actor — no storage reads at runtime
    val entitlementsByProductId get() = snapshot.entitlementsByProduct
    val active get() = mergeEntitlementsPrioritized((snapshot.backingActive + snapshot.activeDeviceEntitlements + web).toList()).toSet()
    // ...
}
```

---

## Wiring (DependencyContainer)

```kotlin
// 1. Compute initial states from storage (synchronous)
val initialIdentity = createInitialIdentityState(storage, deviceHelper.appInstalledAtString)
val initialEntitlements = createInitialEntitlementsState(storage)

// 2. Create single root actor
val sdkActor = Actor(
    SdkState(identity = initialIdentity, entitlements = initialEntitlements),
    CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher()),
)

// 3. Install interceptors
DebugInterceptor.install(sdkActor, name = "Sdk")

// 4. Create facades with scoped projections
entitlements = Entitlements(
    storage = storage,
    actor = sdkActor.entitlementsState(),
    actorScope = ioScope,
)

configManager = ConfigManager(
    actor = sdkActor.configState(),
    // ... other deps
)

identityManager = IdentityManager(
    actor = sdkActor.identityState(),
    configActor = sdkActor.configState(),
    // ... other deps
)
```

---

## Cross-State Coordination

Identity actions need to trigger config actions (e.g. fetch assignments).
Since each context sees only its own slice, cross-state dispatch uses the
other domain's `StateActor` + context:

```kotlin
// In IdentityContext:
val configState: StateActor<SdkConfigState>
val configCtx: ConfigContext get() = configManager

// In an identity action:
object FetchAssignments : Actions({
    // Dispatches on the config actor, awaiting completion
    configState.dispatchAwait(configCtx, SdkConfigState.Actions.FetchAssignments)
    actor.update(Updates.AssignmentsCompleted)
})
```

Both scoped projections route through the same root `Actor<SdkState>`, so
state updates from either domain are serialized by the same CAS mechanism.

---

## Testing

**Pure reducer tests — no mocks, no coroutines:**

```kotlin
@Test fun `identify sets userId`() {
    val initial = IdentityState(appInstalledAtString = "2024-01-01")
    val next = IdentityState.Updates.Identify("user_123").reduce(initial)
    assertEquals("user_123", next.appUserId)
}
```

**Integration tests — create standalone actor:**

```kotlin
@Test fun `configure marks identity ready`() = runTest {
    val actor = Actor(createInitialIdentityState(storage, "2024-01-01"), backgroundScope)
    val manager = IdentityManager(
        actor = actor.asStateActor(),
        configActor = sdkActor.configState(),
        // ...
    )
    manager.configure()
    // verify state transitions...
}
```

---

## Migration Order

1. **Phase 1: Identity** -- Complete. State + Updates + Actions + Context + Facade.
2. **Phase 2: Entitlements** -- Complete. Synchronous mutations via actor.update + persist.
3. **Phase 3: Config** -- Complete. SdkConfigState + Actions + ConfigContext + ConfigManager facade.
4. **Phase 4: Presentation pipeline** -- Wire to consume actor state instead of manager references.
5. **Phase 5: PaywallRequestManager** -- Actor for request dedup + cache. Eliminates runBlocking + synchronized.

---

## What Disappears

| Deleted | Replaced By |
|---------|-------------|
| 5 mutable sets + ConcurrentHashMap in Entitlements | Single immutable `EntitlementsState` in actor |
| `runBlocking` on property reads | `actor.state.value.*` — no blocking |
| `synchronized` blocks | Actor CAS updates — lock-free |
| Manual storage.write/read in mutations | `persist()` / `createInitialState()` pattern |
| Circular Superwall.instance deps | Context interfaces with explicit deps |
| Intermediate anonymous context objects | Facade-as-context (implements interface directly) |

---

## Threading Model

State updates via `actor.update(reducer)` use `MutableStateFlow.update` (CAS retry) —
lock-free, thread-safe, and routed through interceptor chains. No dispatcher needed.

Actions via `actor.dispatch(ctx, action)` launch in the actor's scope (single-thread
dispatcher). Multiple actions run concurrently within that scope.

```
facade.method()
    |
    |-- actor.update(reducer)     // synchronous CAS — instant state change
    |-- persist(storable, value)  // inline storage write
    |-- actor.dispatch(action)    // fire-and-forget in actor scope
         |
         |-- action reads actor.state.value    // always sees latest
         |-- action calls actor.update(...)    // more state changes
         |-- action calls effect(subAction)    // launches sub-actions
         |-- action calls persist(...)         // storage writes
```

Reducers are pure `(S) -> S`. Actions own their side effects.
The actor serializes state transitions. Effects run concurrently.
