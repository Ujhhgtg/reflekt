# Reflekt: MethodHandle execution backend — plan

## Decisions (agreed)
- Discovery/metadata stays on `java.lang.reflect` (`Spec`s, superclass walk, `ReflectionCache`, `.self`). Only execution changes.
- Exceptions: **propagate raw** (no `InvocationTargetException`). WeKit catch sites must be fixed in the same rollout (see Phase 5).
- **Benchmark gate first**: handle backend ships behind an internal switch; becomes default only if a device benchmark wins.
- Public API may break (2.0-style). Fixed-arity overloads allowed.
- Verification: JVM (JDK 21) unit tests for semantics + a device benchmark run by the user.

## Findings from my own review (differences from the earlier proposal)
1. **`invokeExact` on a void setter is a bug in the proposal.** Kotlin emits the call-site descriptor from the expression type (`Object`), so `setter.invokeExact(r, v)` against a `(Object,Object)void` handle throws `WrongMethodTypeException`. Fix: adapt setters to return `Object` (`asType` turns void into null) or use `invoke`. Same care for every handle: adapted type must equal the call-site type exactly.
2. **Performance on ART is unproven.** `asType`/`asSpreader` produce `Transformer` adapter chains that ART interprets; a direct `unreflect` handle is native, adapted ones may not be. Hence the benchmark gate. The fixed-arity path (`asType` only, no spreader) is the most likely to win, so benchmark it separately from the spread path.
3. **Cold cost.** Building a handle (`unreflect` + `asType` + `asSpreader`) costs far more than one `Method.invoke`. WeKit does many one-shot `obj.reflekt().invokeMethod("x")` calls. This is why access objects must be built lazily and shared through `ReflectionCache` (not rebuilt per `InstanceReflect*` wrapper). Non-cacheable specs (lambda conditions) yield fresh wrappers each call and will pay the cold cost every time; the benchmark must include this case. Fallback if it hurts: hybrid (Method.invoke for the first N calls, handle after).
4. **Use `lazy(PUBLICATION)` or a `@Volatile` field**, not default `by lazy` (synchronized on every read).
5. **Behaviour changes to test and document:** wrong receiver type -> `ClassCastException` (was `IllegalArgumentException`); bad arg type -> `ClassCastException`; wrong arg count -> `IllegalArgumentException`; null receiver on instance method -> `NullPointerException`. Widening (Integer arg to `long` param) must be verified: `Method.invoke` allows it, and `asType` from `Object` should too on JVM; **unverified on ART**, so the device benchmark must include it.
6. **Constructors currently skip `makeAccessible()`** (methods/fields do it). Fix as part of this change.
7. **Hooks:** WeKit hooks the same `Method` objects via Xposed/ArtHook. Handle invocation of a hooked method must still hit the hook; needs an on-device check (part of the benchmark app).
8. `InstanceReflectedConstructor` takes an unused `instance`; leave as is unless you want it removed.
9. Fields: use `unreflectGetter`/`unreflectSetter` (not `VarHandle`) for the reasons in the earlier proposal (volatile, final, private access). Getter/setter lazy independently. Static fields: `dropArguments` receiver.

## Phases

### Phase 0 — done
`Reflect.invokeMethod(name, instance, ...)` now uses `.invoke(instance, ...)` (on master, 69a4106).

### Phase 1 — backend abstraction (no behaviour change)
- New `internal` files under `reflected/`: `MethodAccess`, `FieldAccess`, `ConstructorAccess`, each with a reflection implementation and a handle implementation, selected by `internal var ReflektBackend` (`REFLECTION` default for now).
- `ReflectedMethod/Field/Constructor` and the `Instance*` variants hold an access object and delegate to it. `makeAccessible()` moves into access construction (once), including constructors.
- `InstanceReflect` and `erase()`/`of()` pass the existing `ReflectedX` (and its access) instead of `.self`; delete the `(instance, Method)` constructors.
- Tests: all existing tests must pass unchanged.

### Phase 2 — handle implementations
- Method: `unreflect(m).asFixedArity()`; static -> `dropArguments(0, Object)`; `asType(genericMethodType(n [+1]))`; spread variant via `asSpreader(Object[].class, n)`. Never `invokeWithArguments`.
- Constructor: `unreflectConstructor` -> `asType(generic)` -> `asSpreader`.
- Field: getter `(Object)->Object`, setter `(Object,Object)->Object` (returns null; see finding 1).
- Fixed-arity handles for 0-3 args, built lazily per arity; spread handle only for the vararg fallback.
- Exceptions propagate raw; no wrapping anywhere.

### Phase 3 — API
- Add `invoke()`, `invoke(a)`, `invoke(a,b)`, `invoke(a,b,c)` overloads on `ReflectedMethod`, `InstanceReflectedMethod`, `newInstance` on constructors; keep `vararg` versions.
- Fix `invokeStatic` and `getStatic`/`setStatic` on the new backend.

### Phase 4 — tests (JDK 21) and benchmark
- New tests: static/instance, private, final field get/set, void return, primitive return/param, boxed widening, varargs-as-array, superclass member, exception propagation is raw, wrong-type errors as in finding 5, cache sharing (same access object across `InstanceReflect` calls), lazy (no handle built when only `.self` is used).
- Benchmark source (plain Kotlin main or Android app snippet the user runs on a device): `Method.invoke` vs handle for 0/1/2/3/N args, field get/set, constructor, cold first call, non-cacheable spec, widening case, and a hooked method. Report ns/op.
- **Gate:** flip the default to `HANDLE` only if the device numbers show a clear win on the hot path and no cold-call regression that matters. Otherwise ship the abstraction with `REFLECTION` default (or the hybrid).

### Phase 5 — WeKit-Dev rollout (only after the gate passes)
- Fix catches of `InvocationTargetException` that wrap reflekt calls: `WePacketDispatcher`, `AutoSpeechToText` (verify each actually goes through reflekt; `HybridClassLoader`, `ActivityProxy`, `ArtHookBridgeRuntime`, `PythonRuntimeLoader`, `WeJvmToolBindings` appear to use raw `Method.invoke` and are unaffected, but `WeJvmToolBindings.guard` is worth a look).
- Sweep for other `catch (e: Exception)` patterns depending on wrapped causes (`e.cause`, `targetException`).
- Bump reflekt version, release notes listing the behaviour changes in finding 5 and the exception change.

## Out of scope
`VarHandle` field backend, atomic/memory-order field API (revisit only if wanted), any change to spec/discovery code.

## Open items I cannot resolve here
- Any ART numbers (I only have desktop JDK 21).
- Whether WeKit's hooking layer intercepts calls made through a `MethodHandle` (needs a device).
