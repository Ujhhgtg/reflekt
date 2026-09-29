# Reflekt: MethodHandle execution backend — plan

## Status
- Phases 1-4 implemented in reflekt (`reflected/Access.kt` backends, `compiled()`, `Reflekt.defaults`, unified call convention, `ofNone()`, unwrapped exceptions). 91 JVM tests pass (`BackendTest` runs every case on both backends).
- JVM (JDK 21, HotSpot) numbers from `src/test/.../bench/Benchmark.kt`, one backend per process, ns/op:

  | case | reflection | compiled |
  |---|---|---|
  | invoke() 0 args | 16 | 14 |
  | invoke(a, b) | 12.5 | 13 |
  | invoke 7 args (spread) | 270 | 145 |
  | field get / set | 8 / 10 | 10 / 10 |
  | newInstance() | 124 | 17 |
  | cold lookup + first call | 2600 | 8800 |

  Takeaway on HotSpot: `compiled()` pays off for constructors and 6+ argument calls; cold use is ~3.4x more expensive, confirming opt-in. **ART numbers still needed** (run `runReflektBenchmark()` on a device, one backend per process).
- Phase 5 (WeKit-Dev): A, A2, B done on WeKit-Dev branch `claude/exciting-johnson-5bqj0f` (a9cb73a), which bumps the submodule to 9e5227e. `:app:compileDebugKotlin` is clean. Extra sites found by compiling (4 removed `InstanceReflectedField.get(instance)` calls) and by a temporary `@Deprecated` audit of every unbound `invoke`/`get`/`set` (8 static calls with a `null` receiver; 2 field loops rebound with `of()`). `:app:testDebugUnitTest`: 354 pass, 6 skipped, 7 fail for environment reasons only (root user, non-UTF-8 locale, missing WeChat APKs, no proot). Full `./x build` not verified here (native libvpx download blocked). C (adopting `compiled()`) waits for ART numbers.

## Decisions (agreed)
- Discovery/metadata stays on `java.lang.reflect` (`Spec`s, superclass walk, `ReflectionCache`, `.self`). Only execution changes.
- Exceptions: **propagate raw** (no `InvocationTargetException`). WeKit catch sites must be fixed in the same rollout (see Phase 5).
- **Handles are opt-in, reflection stays the default** (most calls are one-shot; building a handle costs more than one `Method.invoke`). Enabled per lookup with `compiled()` / `compiled(true|false)` in the spec DSL, with a global default. A global default for `superclass` is added too. A device benchmark is still run, but only to document when `compiled()` pays off, not to gate anything.
- Public API may break (2.0-style). Fixed-arity overloads allowed.
- Verification: JVM (JDK 21) unit tests for semantics + a device benchmark run by the user.

## Findings from my own review (differences from the earlier proposal)
1. **`invokeExact` on a void setter is a bug in the proposal.** Kotlin emits the call-site descriptor from the expression type (`Object`), so `setter.invokeExact(r, v)` against a `(Object,Object)void` handle throws `WrongMethodTypeException`. Fix: adapt setters to return `Object` (`asType` turns void into null) or use `invoke`. Same care for every handle: adapted type must equal the call-site type exactly.
2. **Performance on ART is unproven.** `asType`/`asSpreader` produce `Transformer` adapter chains that ART interprets; a direct `unreflect` handle is native, adapted ones may not be. Hence handles are opt-in. The fixed-arity path (`asType` only, no spreader) is the most likely to win, so benchmark it separately from the spread path.
3. **Cold cost.** Building a handle (`unreflect` + `asType` + `asSpreader`) costs far more than one `Method.invoke`, and WeKit does many one-shot calls. Resolved by making handles opt-in (see "Opt-in API"). Even when opted in, the handle is built lazily on first invoke (never at lookup, so hook-only `.self` use stays free) and shared through `ReflectionCache`. A `compiled()` lookup with a lambda condition is not cacheable and rebuilds the handle each time; document that `compiled()` belongs on cacheable, hot lookups.
4. **Use `lazy(PUBLICATION)` or a `@Volatile` field**, not default `by lazy` (synchronized on every read).
5. **Behaviour changes to test and document:** wrong receiver type -> `ClassCastException` (was `IllegalArgumentException`); bad arg type -> `ClassCastException`; wrong arg count -> `IllegalArgumentException`; null receiver on instance method -> `NullPointerException`. Widening (Integer arg to `long` param) must be verified: `Method.invoke` allows it, and `asType` from `Object` should too on JVM; **unverified on ART**, so the device benchmark must include it.
6. **Constructors currently skip `makeAccessible()`** (methods/fields do it). Fix as part of this change.
7. **Hooks:** WeKit hooks the same `Method` objects via Xposed/ArtHook. Handle invocation of a hooked method must still hit the hook; needs an on-device check (part of the benchmark app).
8. `InstanceReflectedConstructor` takes an unused `instance`; leave as is unless you want it removed.
9. Fields: use `unreflectGetter`/`unreflectSetter` (not `VarHandle`) for the reasons in the earlier proposal (volatile, final, private access). Getter/setter lazy independently. Static fields: `dropArguments` receiver.

## Opt-in API
- `Reflekt.defaults` (object, `@Volatile` fields): `compiled: Boolean = false`, `superclass: Boolean = false`.
- `Spec` gets `var compiled` initialised from `Reflekt.defaults.compiled` when the spec is created, plus `fun compiled()` (= true) and `fun compiled(value: Boolean)`, mirroring `superclass()` / `superclass(value)`. Applies to `MethodSpec`, `FieldSpec`, `ConstructorSpec`.
  `instance.reflekt().firstMethod { name = "x"; compiled() }.invoke()`
- `Spec.superclass` likewise initialises from `Reflekt.defaults.superclass`. The convenience helpers that take an explicit flag (`invokeMethod`, `getField`, `setField`) change their parameter to `Boolean? = null`, meaning "use the global default". `setField` currently defaults to `true` while `getField`/`invokeMethod` default to `false`. **Decided:** all three follow the global default (unified). This changes `setField`'s behaviour when the global is `false`; call it out in the release notes.
- Cache: `compiled` is added to `staticCacheKeyParts()` so compiled and plain lookups of the same spec get separate wrappers. `superclass` is already in the key, so changing the global default is safe.
- A wrapper with `compiled = false` never builds a handle; with `true` it builds one lazily on first use. The reflective path stays exactly as today (`makeAccessible()` once, `Method.invoke`).
- **Exception consistency (decided):** raw propagation on both paths. The reflective path unwraps `InvocationTargetException` and rethrow the cause, so both paths throw the same exception. This still breaks the WeKit catch sites in Phase 5 by default, not only for `compiled()`.

## Unified call convention (no `xxxStatic`)
Every call behaves like invoking the member directly / like a `MethodHandle` of that member: **an instance member takes its receiver as the first argument; a static member takes none.** Static-ness is read from `Modifier.isStatic(self.modifiers)` once, when the access object is built.

| Wrapper | instance member | static member |
|---|---|---|
| `ReflectedMethod.invoke(...)` | `invoke(instance, a, b)` | `invoke(a, b)` |
| `InstanceReflectedMethod.invoke(...)` | `invoke(a, b)` (bound receiver) | `invoke(a, b)` (bound receiver ignored) |
| `ReflectedField.get / set` | `get(instance)`, `set(instance, v)` | `get()`, `set(v)` |
| `InstanceReflectedField.get / set` | `get()`, `set(v)` | `get()`, `set(v)` |
| `ReflectedConstructor.newInstance(...)` | `newInstance(a, b)` | n/a |
| `Reflect.invokeMethod(name, ...)` | `invokeMethod(name, instance, a)` | `invokeMethod(name, a)` |
| `InstanceReflect.invokeMethod(name, ...)` | `invokeMethod(name, a)` | `invokeMethod(name, a)` |

- Renamed: `InstanceReflectedField.erase()` -> `ofNone()` (matches `of(instance)`; no WeKit-Dev usages, already done in code).
- Removed: `invokeStatic`, `getStatic`, `setStatic`. This also **supersedes the Phase 0 fix's signature**: `Reflect.invokeMethod(name, instance, vararg args, superclass)` becomes `invokeMethod(name, vararg args, superclass)`, with the receiver (if the resolved method is non-static) as the first vararg. The Phase 0 change (`invoke(instance, ...)` instead of `invokeStatic`) is an intermediate state.
- `ReflectedMethod.invoke` becomes `invoke(vararg args: Any?)` plus fixed-arity overloads `invoke()`, `invoke(a)`, ..., `invoke(a, b, c)`. The argument count is *total*, i.e. `parameterCount + (if static 0 else 1)`; fixed-arity overloads therefore map 1:1 to handle types with no dropArguments/bindTo adapters. `T` on `ReflectedMethod<T>` no longer types the receiver (it can't be expressed for a static method); receiver is checked at call time.
- Arity is validated up front on both backends with a clear `IllegalArgumentException` (e.g. `static method Foo.bar(String) takes 1 argument, got 2; do not pass a receiver`). This turns the most likely migration mistake, `invoke(null, x)` on a static method, into an immediate, self-explanatory failure instead of a confusing reflection error.
- Handle backend: no `dropArguments` receiver shim any more. The handle is `unreflect(...)`'s natural type (receiver first for instance members), adapted with `asType(genericMethodType(total))`; spread variant `asSpreader(Object[], total)`. `InstanceReflected*` reuse the same shared handle and prepend the bound instance (for static members, don't). Field getter `(recv?) -> Object`, setter `(recv?, value) -> Object` (see finding 1).
- Reflection backend: static -> `self.invoke(null, *args)`; instance -> `self.invoke(args[0], *args.copyOfRange(1, n))`.
- `InstanceReflectedField` currently also exposes `get(instance)` / `set(instance, value)` overloads. **Decided (say if you disagree):** remove them; `ofNone()` / `of(instance)` already cover that, and they contradict "bound means no receiver argument".

## Phases

### Phase 0 — done
`Reflect.invokeMethod(name, instance, ...)` now uses `.invoke(instance, ...)` (on master, 69a4106).

### Phase 1 — backend abstraction (no behaviour change)
- New `internal` files under `reflected/`: `MethodAccess`, `FieldAccess`, `ConstructorAccess`, each with a reflection implementation and a handle implementation, chosen per wrapper by the spec's `compiled` flag.
- Add `Reflekt.defaults`, `Spec.compiled`, `Spec.superclass` default (see "Opt-in API").
- `ReflectedMethod/Field/Constructor` and the `Instance*` variants hold an access object and delegate to it. `makeAccessible()` moves into access construction (once), including constructors.
- `InstanceReflect` and `ofNone()`/`of()` pass the existing `ReflectedX` (and its access) instead of `.self`; delete the `(instance, Method)` constructors.
- Tests: all existing tests must pass unchanged.

### Phase 2 — handle implementations
- Method: `unreflect(m).asFixedArity()`; static -> `dropArguments(0, Object)`; `asType(genericMethodType(n [+1]))`; spread variant via `asSpreader(Object[].class, n)`. Never `invokeWithArguments`.
- Constructor: `unreflectConstructor` -> `asType(generic)` -> `asSpreader`.
- Field: getter `(Object)->Object`, setter `(Object,Object)->Object` (returns null; see finding 1).
- Fixed-arity handles for 0-3 args (compiled path only), built lazily per arity; spread handle only for the vararg fallback.
- Exceptions propagate raw; no wrapping anywhere, on the compiled and the reflective path.

### Phase 3 — API
- Add `invoke()`, `invoke(a)`, `invoke(a,b)`, `invoke(a,b,c)` overloads on `ReflectedMethod`, `InstanceReflectedMethod`, `newInstance` on constructors; keep `vararg` versions.
- Delete `invokeStatic` / `getStatic` / `setStatic`; implement the unified convention above on both backends.

### Phase 4 — tests (JDK 21) and benchmark
- New tests: static/instance, private, final field get/set, void return, primitive return/param, boxed widening, varargs-as-array, superclass member, exception propagation is raw, wrong-type errors as in finding 5, cache sharing (same access object across `InstanceReflect` calls), lazy (no handle built when only `.self` is used).
- Benchmark source (plain Kotlin main or Android app snippet the user runs on a device): `Method.invoke` vs handle for 0/1/2/3/N args, field get/set, constructor, cold first call, non-cacheable spec, widening case, and a hooked method. Report ns/op.
- No gate: reflection remains the default. Use the numbers to write guidance (which arities/members benefit from `compiled()`), and to decide whether the fixed-arity overloads are worth keeping.
- Tests for the unified convention: instance vs static x `ReflectedX` vs `InstanceReflectedX` for methods and fields (see table); wrong receiver/arity -> the explicit `IllegalArgumentException`; `Reflect.invokeMethod` static and instance; both backends behave identically.
- Tests for the opt-in API: default off; `compiled()`/`compiled(false)`/global default; spec-level flag overrides global; cache keys differ; `compiled` wrapper builds no handle until first invoke; global `superclass` default honoured and part of cache key.

### Phase 5 — WeKit-Dev migration
WeKit-Dev vendors reflekt as the git submodule `libs/common/reflekt` (`https://github.com/Ujhhgtg/reflekt`), consumed via `implementation(project(":libs:common:reflekt"))`. Migration = land the reflekt changes on `master`, then in WeKit-Dev change call sites **and** bump the submodule pointer in one commit, so the app never builds against a mismatched pair.

**Correction to earlier text:** `setField` defaults differ by receiver. `InstanceReflect.setField` (what `obj.reflekt().setField(...)` uses) defaults to `superclass = true`; `Reflect.setField` (class receiver) defaults to `false`. `getField` and `invokeMethod` default to `false` on both. After unification all follow `Reflekt.defaults.superclass` (default `false`), so only instance-receiver `setField` calls change behaviour.

**A. `setField` call sites that relied on the old `true` default (must add `superclass = true`)** — found by scanning WeKit-Dev at `88f47be`:
- `features/api/net/WePacketHelper.kt:474-481` — 8 calls (`"a"`, `"b"`, `"c"`, `"d"`, `"e"`, `"f"`, `"l"`, `"n"`) on the obfuscated request builder.
- `features/items/moments/AntiMomentsDelete.kt:204` — `setField("field_content", ...)`.
- `features/items/moments/AntiMomentsDelete.kt:213` — `setField("field_sourceType", value)`.
Already explicit, no change: `WeMessageApi.kt:950-953`, `JavaEngine.kt:1681/1683`. Not reflekt: `JvmReflector.setField`, the BeanShell `setField` binding, `WeJvmToolBindings.jvmSetField`.
`getField`/`invokeMethod`/`firstX {}` sites are unchanged (global default stays `false`). Do **not** set `Reflekt.defaults.superclass = true` in WeKit to paper over this: it would widen every lookup and can change which member `firstMethod`/`firstField` returns.
Safety net: after the edits, grep again for `setField(` without `superclass` and re-verify against the reflekt tests; a missed site fails at runtime with `NoSuchElementException` (field not found in the concrete class), not silently.

**A2. `xxxStatic` and static-receiver call sites (compile errors and runtime arity errors)**
- Compile errors, fix mechanically by dropping `Static`: `invokeStatic(...)` -> `invoke(...)`, `getStatic()` -> `get()`, `setStatic(v)` -> `set(v)`. 16 occurrences in 11 files: `WeAuthApi`, `WeDatabaseApi`, `WeMessageApi` (5), `WeServiceApi` (2), `WeTextStatusApi`, `WeNetSceneApi`, `WeAlertDialogApi`, `WeMomentsApi`, `JavaEngine`, `DisableLowAvailableStorageDetection`, `KillHostUtils`. (Verify each is on a reflekt type; some may be unrelated.)
- **Not compile errors, runtime arity errors** (static member called with a leading `null` receiver): `WePacketHelper.kt:500` `.invoke(null, rr, cbProxy, false)` and `WeConversationApi.kt:701` `.invoke(null, convId)` are reflekt `firstMethod {}` results on static methods; change to `invoke(rr, cbProxy, false)` / `invoke(convId)`. `Reflect.invokeMethod(name, null, superclass = true)` in `WePacketHelper` (`getNetQueue`, static) becomes `invokeMethod(name, superclass = true)`. Sweep for the rest: every `.invoke(null, ...)`, `.get(null)`, `.set(null, ...)` whose receiver is a reflekt `ReflectedMethod`/`ReflectedField` (many other `.invoke(null, ...)` in WeKit are raw `java.lang.reflect.Method` from DexKit `.method` and must NOT change). A wrong one fails immediately with the explicit arity message.

**B. Exception unwrapping (reflekt now throws the target's exception, not `InvocationTargetException`)** — reflekt-backed catch sites:
- `features/api/net/listener/WePacketDispatcher.kt:69-84`: `catch (e: InvocationTargetException)` with `e.cause is NullPointerException`. Change to `catch (e: NullPointerException)`, log `e` instead of `e.cause`, drop the rethrow branch and the now-unused import.
- `features/items/chat/AutoSpeechToText.kt:89`: `catch (_: InvocationTargetException)` around `transformMethod.invoke(...)` (a reflekt `InstanceReflectedMethod`). It currently swallows any target exception, so use `catch (_: Exception)` to keep that behaviour (the comment names a NullPointerException, so `NullPointerException` alone is a tighter alternative; pick one when implementing).
Not reflekt, unaffected (raw `Method.invoke`): `HybridClassLoader`, `ActivityProxy`, `ArtHookBridgeRuntime`, `PythonRuntimeLoader.unwrap`, `WeJvmToolBindings.guard` (uses `JvmReflector`). `StickerPanel.kt:568` and `PythonDexHostImpl`/`PythonTaskHostImpl` use `error.cause ?: error` on arbitrary throwables; leave them, but double check none of them receive a reflekt-thrown exception.
Also grep for `catch (e: Exception)` blocks that read `e.cause` after a reflekt call (none found beyond the above at `88f47be`).

**C. Adopt the new API where it earns its keep (optional, after A and B are green)**
- `compiled()` only on hot, cacheable lookups, chosen from the benchmark. Candidates to check: per-message/packet hook bodies such as `WePacketDispatcher` (`getUri`, `getType`, `getReqObj`, `getField("a")`), `WePacketHelper` response handling, `MessageInfo.getFieldByName` (called per message), `ContactBean`/`ConversationBean` field reads (per list row), `WeConversationListViewApi` `getField("itemView")` (per bind). Leave one-shot and startup lookups alone.
- Replace the repeated `superclass = true` / `getField(name, true)` noise with explicit per-call values as they are (do not switch the WeKit global default).

**D. Rollout order**
1. Land reflekt Phases 1-4 on reflekt `master`.
2. WeKit-Dev branch: bump submodule, apply A and B in the same commit, build, run the app's unit tests (`app/src/test`) and a device smoke test of packet interception, Anti Moments Delete, speech-to-text, and quote-relation insert (the flows that touch the changed sites).
3. Only then apply C, one feature at a time, with before/after numbers.
Rule: never bump the submodule without A, A2 and B in the same change.

## Out of scope
`VarHandle` field backend, atomic/memory-order field API (revisit only if wanted), any change to spec/discovery code.

## Open items I cannot resolve here
- Any ART numbers (I only have desktop JDK 21).
- Whether WeKit's hooking layer intercepts calls made through a `MethodHandle` (needs a device).
