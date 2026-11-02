# backend:api:contract

The contract test suite that every `BlobStore` must pass, and a small in-memory
store. A new backend is done when it passes
([PLAN.md §6.1](../../../docs/PLAN.md#61-blobstore-interface)).

```kotlin
class WebDavContractTest : BlobStoreContract() {
    override suspend fun newStore(): BlobStore = ... // new, empty, reachable
    override suspend fun unreachableStore(): BlobStore = ... // for example, nothing listening
}
```

Add the module to the backend's test source set, and the test class to a test
source set of each target it should run on (`commonTest` for all of them):

```kotlin
commonTest.dependencies {
    implementation(projects.backend.api.contract)
}
```

`InMemoryBlobStore` is for tests of the code above the backends, such as sync.
Setting `reachable = false` makes it act like a backend that went away.

**Dependency rule:** depends on `backend:api`, kotlin.test, coroutines-test and
atomicfu. Only test source sets may depend on this module.

## Design

- **A module of its own, not a test source set of `backend:api`.** A test source
  set cannot be consumed by another module's tests in Kotlin Multiplatform. So the
  suite is in `commonMain` here, and kotlin.test stays out of `backend:api` and of
  anything that ships. On the JVM and Android the module asks for
  `kotlin-test-junit` itself, because Gradle only picks a flavour of kotlin.test
  for test source sets.
- **An abstract class with `@Test` methods.** Subclasses run on every target,
  from another module too. That gives a backend a report per contract rule, for
  each subclass.
- **Real threads.** The race tests run on `Dispatchers.Default`, in 30 rounds with
  8 writers. A flaky failure in this suite is a real bug in the store.
- **Not counted by the coverage gate:** it is test code, and is proved by the
  tests below instead.

## Tests

`InMemoryBlobStoreTest` runs the suite against the in-memory store, so the suite
can pass. `ContractCatchesBrokenStoresTest` runs tests of the suite against stores
with one fault each (overwriting, string prefixes, looking empty when gone, no
path checks), and they must fail: ADR-0004 rests on the suite catching those.
