package statoscope.codegen.processor

import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Nullability

/**
 * Backs `statoscope.codegen`'s `@Reducer`/`@SubState`/`@OnChildAction` — see each annotation's
 * own doc for the design; this class is the implementation, never referenced directly by a
 * consumer (applied via the `ksp(project(":codegen-processor"))` Gradle configuration, never
 * `implementation`).
 *
 * Two passes over every `@Reducer`-annotated `object`:
 *
 * 1. Validate each has a nested `State`/`When` and a matching `update` function, and record
 *    which reducer object owns which `State` type (`stateFqName -> reducer object`) — this map
 *    is what lets a parent reference a child's own generated `config` instead of rebuilding it.
 * 2. For each reducer, collect its `State`'s `@SubState` properties and its own
 *    `@OnChildAction`-annotated functions (matched to a child by TYPE, not by name), then emit
 *    one file per reducer: a stable top-level `val` per `SubScope` (never built inline inside
 *    `subScopes`'s `listOf(...)` — `ScopeStore.childStore(subScope)` looks it up by reference
 *    identity, so an inline-built or `get()`-recomputed instance would silently break it for
 *    any caller outside this generated file) plus the `<Reducer>.config` extension property
 *    referencing them.
 */
class SubScopeProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private data class ReducerInfo(
        val declaration: KSClassDeclaration,
        val stateClass: KSClassDeclaration,
        val whenClass: KSClassDeclaration
    ) {
        val stateFqName: String get() = stateClass.qualifiedName!!.asString()
        val whenFqName: String get() = whenClass.qualifiedName!!.asString()
    }

    private data class ChildSlot(val propertyName: String, val childReducer: ReducerInfo)

    private data class ReducerInjectedSlot(val propertyName: String, val keyName: String)

    private data class ParentStateSlot(val propertyName: String, val parentReducer: ReducerInfo)

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val reducerObjects = resolver.getSymbolsWithAnnotation("statoscope.codegen.Reducer")
            .filterIsInstance<KSClassDeclaration>()
            .toList()

        // Pass 1: validate + build the State-type -> owning-reducer map.
        val reducers = reducerObjects.mapNotNull { validate(it) }
        val byStateFqName = reducers.associateBy { it.stateFqName }

        // Pass 1.5: who's each reducer's real direct parent (if any) — every OTHER reducer's
        // own @SubState properties, matched the same way resolveSlot validates them, but without
        // re-emitting errors here (resolveSlot's own call, per-reducer in generateConfig, is the
        // one place that reports those). Then resolve @ParentState exactly ONCE per reducer —
        // memoized here, not recomputed per caller, so a malformed @ParentState property is
        // reported once, not once per place that looks it up (itself, and any parent checking
        // whether to wire a superstateKey for it).
        val childToParent = buildChildToParentMap(reducers, byStateFqName)
        val parentStateSlotByChildStateFqName = reducers.associate { reducer ->
            reducer.stateFqName to resolveParentStateSlot(reducer, childToParent)
        }

        // Pass 2: generate, now that every reducer's identity (and @ParentState wiring) is known.
        reducers.forEach { reducer -> generateConfig(reducer, byStateFqName, parentStateSlotByChildStateFqName) }

        return emptyList()
    }

    /** Best-effort, non-erroring: a malformed `@SubState` property is reported once, by
     *  [resolveSlot], when its own reducer is generated — this is only building a lookup. */
    private fun buildChildToParentMap(
        reducers: List<ReducerInfo>,
        byStateFqName: Map<String, ReducerInfo>
    ): Map<String, ReducerInfo> {
        val result = mutableMapOf<String, ReducerInfo>()
        for (parent in reducers) {
            for (property in parent.stateClass.getDeclaredProperties()) {
                if (property.annotations.none { it.shortName.asString() == "SubState" }) continue
                val childStateFqName = (property.type.resolve().declaration as? KSClassDeclaration)
                    ?.qualifiedName?.asString() ?: continue
                val childReducer = byStateFqName[childStateFqName] ?: continue
                result[childReducer.stateFqName] = parent
            }
        }
        return result
    }

    private fun validate(declaration: KSClassDeclaration): ReducerInfo? {
        if (declaration.classKind != ClassKind.OBJECT) {
            logger.error("@Reducer must be on an object, not a ${declaration.classKind}", declaration)
            return null
        }
        val stateClass = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .find { it.simpleName.asString() == "State" }
        if (stateClass == null) {
            logger.error("@Reducer object '${declaration.simpleName.asString()}' needs a nested State class", declaration)
            return null
        }
        val whenClass = declaration.declarations
            .filterIsInstance<KSClassDeclaration>()
            .find { it.simpleName.asString() == "When" }
        if (whenClass == null) {
            logger.error("@Reducer object '${declaration.simpleName.asString()}' needs a nested When type", declaration)
            return null
        }
        val update = declaration.getDeclaredFunctions().find { it.simpleName.asString() == "update" }
        if (update == null || update.parameters.size != 4) {
            logger.error(
                "@Reducer object '${declaration.simpleName.asString()}' needs " +
                    "fun update(state: State, action: When, effects: EffectsState<When>, dependencies: Dependencies): State",
                declaration
            )
            return null
        }

        return ReducerInfo(declaration = declaration, stateClass = stateClass, whenClass = whenClass)
    }

    private fun generateConfig(
        reducer: ReducerInfo,
        byStateFqName: Map<String, ReducerInfo>,
        parentStateSlotByChildStateFqName: Map<String, ParentStateSlot?>
    ) {
        val reducerName = reducer.declaration.simpleName.asString()
        val stateName = "$reducerName.State"
        val whenName = "$reducerName.When"
        val packageName = reducer.declaration.packageName.asString()

        val slots = reducer.stateClass.getDeclaredProperties()
            .filter { prop -> prop.annotations.any { it.shortName.asString() == "SubState" } }
            .mapNotNull { property -> resolveSlot(property, byStateFqName) }
            .toList()

        val onChildActionsByChildStateFqName = resolveOnChildActions(reducer, byStateFqName)
        val reducerInjectedSlots = resolveReducerInjectedSlots(reducer)
        val ownParentStateSlot = parentStateSlotByChildStateFqName[reducer.stateFqName]

        // This reducer's own SuperstateKey<State> — generated here, in ITS OWN file, only if at
        // least one @SubState child actually declared @ParentState pointing back at it. Same
        // naming convention wherever it's referenced from (here, and from each such child's own
        // generated update() wrapper below) — a pure function of this reducer's name, so no
        // cross-file lookup is needed to agree on the name, only on whether to emit the `val`
        // itself (only this, the parent's own file, does that).
        val superstateKeyName = "${reducerName.replaceFirstChar { it.lowercase() }}SuperstateKey"
        val slotsNeedingSuperstateKey = slots.filter { slot ->
            parentStateSlotByChildStateFqName[slot.childReducer.stateFqName]?.parentReducer?.stateFqName == reducer.stateFqName
        }.toSet()

        // Each SubScope is its own stable, eagerly-initialized top-level `val` — NOT built
        // inline inside subScopes' listOf(...) — because ScopeStore.childStore(subScope) looks
        // it up by reference identity. An extension `get()` property would rebuild a fresh
        // SubScope on every read (and extension properties can't hold `by lazy` backing state
        // at all, since they have no backing field), silently breaking childStore() for anyone
        // outside this file.
        val subScopeValNames = slots.associateWith { slot ->
            "${reducerName.replaceFirstChar { it.lowercase() }}${slot.propertyName.replaceFirstChar { it.uppercase() }}SubScope"
        }

        val subScopeDeclarations = slots.joinToString("\n\n") { slot ->
            val childReducerName = slot.childReducer.declaration.simpleName.asString()
            val childStateName = "$childReducerName.State"
            val childWhenName = "$childReducerName.When"
            val onChildAction = onChildActionsByChildStateFqName[slot.childReducer.stateFqName]
                ?.let { functionName -> "$reducerName::$functionName" }
                ?: "null"
            val superstateKeyArg = if (slot in slotsNeedingSuperstateKey) superstateKeyName else "null"
            """
            |val ${subScopeValNames.getValue(slot)}: SubScope<$stateName, $whenName, $childStateName, $childWhenName> =
            |    SubScope(
            |        config = $childReducerName.config,
            |        get = { it.${slot.propertyName} },
            |        set = { state, child -> state.copy(${slot.propertyName} = child) },
            |        onChildAction = $onChildAction,
            |        superstateKey = $superstateKeyArg
            |    )
            """.trimMargin()
        }

        val subScopesBody = if (slots.isEmpty()) {
            "emptyList()"
        } else {
            "listOf(${slots.joinToString(", ") { subScopeValNames.getValue(it) }})"
        }

        val superstateKeyDeclaration = if (slotsNeedingSuperstateKey.isEmpty()) {
            ""
        } else {
            "val $superstateKeyName: SuperstateKey<$stateName> = SuperstateKey(\"$reducerName\")\n\n"
        }

        // No @ReducerInjected/@ParentState properties: keep the plain function reference,
        // unchanged from before either feature existed. With at least one, wrap it in a lambda
        // that re-stamps each one from `dependencies` right after the user's own update()
        // returns — see ReducerInjected's/ParentState's own docs for why this is "fresh as of
        // the last update()," not continuously live on every read.
        val copyAssignments = buildList {
            reducerInjectedSlots.forEach { slot -> add("${slot.propertyName} = dependencies.resolve(${slot.keyName})") }
            ownParentStateSlot?.let { slot ->
                val parentReducerName = slot.parentReducer.declaration.simpleName.asString()
                val parentKeyName = "${parentReducerName.replaceFirstChar { it.lowercase() }}SuperstateKey"
                add("${slot.propertyName} = dependencies.resolve($parentKeyName)")
            }
        }
        val updateBody = if (copyAssignments.isEmpty()) {
            "$reducerName::update"
        } else {
            "{ state, action, effects, dependencies -> " +
                "$reducerName.update(state, action, effects, dependencies).copy(${copyAssignments.joinToString(", ")}) }"
        }

        val superstateKeyImport = if (slotsNeedingSuperstateKey.isNotEmpty() || ownParentStateSlot != null) {
            "import statoscope.core.SuperstateKey\n"
        } else {
            ""
        }

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(aggregating = true, *reducerObjectsFiles(reducer, slots)),
            packageName = packageName,
            fileName = "${reducerName}_Config"
        )

        file.writer().use { writer ->
            writer.write(
                """
                |// GENERATED by statoscope.codegen.processor.SubScopeProcessor from @Reducer — do not edit.
                |package $packageName
                |
                |import statoscope.core.ScopeConfig
                |import statoscope.core.SubScope
                |$superstateKeyImport
                |$superstateKeyDeclaration$subScopeDeclarations
                |
                |val $reducerName.config: ScopeConfig<$stateName, $whenName>
                |    get() = ScopeConfig(
                |        initial = $stateName(),
                |        update = $updateBody,
                |        subScopes = $subScopesBody
                |    )
                |
                """.trimMargin()
            )
        }
    }

    /**
     * `name` is only validated as "present and a String" here — not that it actually names a
     * real top-level `InjectionKey<T>` with a matching `T`, unlike [resolveSlot]/
     * [resolveOnChildActions]'s full symbol-level checks. A wrong name surfaces as a real
     * compile error, just one level removed: the generated file itself fails to compile
     * (`unresolved reference`, or a type mismatch on the generated `.copy(...)` call) rather
     * than KSP reporting it here, pointing at this property. Known, accepted trade-off — see
     * [ReducerInjected]'s own doc.
     */
    private fun resolveReducerInjectedSlots(reducer: ReducerInfo): List<ReducerInjectedSlot> =
        reducer.stateClass.getDeclaredProperties()
            .mapNotNull { property ->
                val annotation = property.annotations
                    .find { it.shortName.asString() == "ReducerInjected" }
                    ?: return@mapNotNull null
                val keyName = annotation.arguments.find { it.name?.asString() == "name" }?.value as? String
                if (keyName.isNullOrBlank()) {
                    logger.error(
                        "@ReducerInjected on '${property.simpleName.asString()}' needs a non-blank name: String " +
                            "naming a top-level InjectionKey val in the same file",
                        property
                    )
                    return@mapNotNull null
                }
                ReducerInjectedSlot(propertyName = property.simpleName.asString(), keyName = keyName)
            }
            .toList()

    /**
     * Matches `@ParentState` by TYPE, like [resolveSlot] does for `@SubState` — no string-named
     * workaround needed the way [resolveReducerInjectedSlots] needs one for `@ReducerInjected`,
     * since the property's own declared type names a real `@Reducer`'s `State` directly. Real
     * compile errors, pointing at the property, for: more than one `@ParentState` property on
     * the same reducer (a reducer has exactly one direct parent), a reducer with no known parent
     * at all (never composed as any other reducer's `@SubState` child), or a property typed as
     * something other than that real parent's own `State`.
     */
    private fun resolveParentStateSlot(reducer: ReducerInfo, childToParent: Map<String, ReducerInfo>): ParentStateSlot? {
        val annotated = reducer.stateClass.getDeclaredProperties()
            .filter { property -> property.annotations.any { it.shortName.asString() == "ParentState" } }
            .toList()
        if (annotated.isEmpty()) return null
        if (annotated.size > 1) {
            logger.error(
                "@Reducer object '${reducer.declaration.simpleName.asString()}' has more than one " +
                    "@ParentState property — only one is allowed, since a reducer has exactly one direct parent",
                reducer.declaration
            )
            return null
        }
        val property = annotated.single()
        val parentReducer = childToParent[reducer.stateFqName]
        if (parentReducer == null) {
            logger.error(
                "@ParentState on '${property.simpleName.asString()}': '${reducer.declaration.simpleName.asString()}' " +
                    "isn't declared as any other @Reducer's @SubState child, so it has no parent to read",
                property
            )
            return null
        }
        val propertyTypeFqName = (property.type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        if (propertyTypeFqName != parentReducer.stateFqName) {
            logger.error(
                "@ParentState property '${property.simpleName.asString()}' must be typed as its real parent, " +
                    "${parentReducer.declaration.simpleName.asString()}.State — got '${property.type.resolve()}'",
                property
            )
            return null
        }
        return ParentStateSlot(propertyName = property.simpleName.asString(), parentReducer = parentReducer)
    }

    private fun resolveSlot(property: KSPropertyDeclaration, byStateFqName: Map<String, ReducerInfo>): ChildSlot? {
        val propertyType = property.type.resolve()
        if (propertyType.nullability != Nullability.NULLABLE) {
            logger.error("@SubState property '${property.simpleName.asString()}' must be nullable", property)
            return null
        }
        val childStateFqName = (propertyType.declaration as? KSClassDeclaration)?.qualifiedName?.asString()
        val childReducer = childStateFqName?.let { byStateFqName[it] }
        if (childReducer == null) {
            logger.error(
                "@SubState property '${property.simpleName.asString()}' names a type with no " +
                    "matching @Reducer object owning it as its State",
                property
            )
            return null
        }
        return ChildSlot(propertyName = property.simpleName.asString(), childReducer = childReducer)
    }

    /**
     * Matches every `@OnChildAction`-annotated function on [reducer] to a child by TYPE — its
     * second parameter's type must be some OTHER `@Reducer`'s `State`. Validates the full
     * `(ChildWhen, ChildState, Dependencies) -> ParentWhen` shape (nullable or not) and reports
     * a real error, pointing at the function, for any mismatch — never a silent no-op.
     */
    private fun resolveOnChildActions(
        reducer: ReducerInfo,
        byStateFqName: Map<String, ReducerInfo>
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val annotated = reducer.declaration.getDeclaredFunctions()
            .filter { fn -> fn.annotations.any { it.shortName.asString() == "OnChildAction" } }
            .toList()

        for (function in annotated) {
            val name = function.simpleName.asString()
            val params = function.parameters
            if (params.size != 3) {
                logger.error(
                    "@OnChildAction function '$name' must take exactly 3 parameters: " +
                        "(childWhen: <Child>.When, childState: <Child>.State, dependencies: Dependencies)",
                    function
                )
                continue
            }

            val childStateFqName = (params[1].type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
            val childReducer = childStateFqName?.let { byStateFqName[it] }
            if (childReducer == null) {
                logger.error(
                    "@OnChildAction function '$name': second parameter must be some @Reducer's " +
                        "State type — '${describeType(params[1])}' isn't one",
                    function
                )
                continue
            }

            val childWhenFqName = (params[0].type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
            if (childWhenFqName != childReducer.whenFqName) {
                logger.error(
                    "@OnChildAction function '$name': first parameter must be " +
                        "${childReducer.declaration.simpleName.asString()}.When (matching the second " +
                        "parameter's State), got '${describeType(params[0])}'",
                    function
                )
                continue
            }

            val dependenciesFqName = (params[2].type.resolve().declaration as? KSClassDeclaration)?.qualifiedName?.asString()
            if (dependenciesFqName != "statoscope.core.Dependencies") {
                logger.error(
                    "@OnChildAction function '$name': third parameter must be statoscope.core.Dependencies, " +
                        "got '${describeType(params[2])}'",
                    function
                )
                continue
            }

            // Nullable or not, both are fine here — a non-null When satisfies a When? slot just
            // as well (Kotlin allows that assignment); only the underlying class must match.
            val returnType = function.returnType?.resolve()
            val returnFqName = (returnType?.declaration as? KSClassDeclaration)?.qualifiedName?.asString()
            if (returnType == null || returnFqName != reducer.whenFqName) {
                logger.error(
                    "@OnChildAction function '$name' must return ${reducer.declaration.simpleName.asString()}.When " +
                        "(nullable or not) — see the return type",
                    function
                )
                continue
            }

            val existing = result[childReducer.stateFqName]
            if (existing != null) {
                logger.error(
                    "@OnChildAction functions '$existing' and '$name' both match " +
                        "${childReducer.declaration.simpleName.asString()} — only one is allowed per child type",
                    function
                )
                continue
            }
            result[childReducer.stateFqName] = name
        }

        return result
    }

    private fun describeType(parameter: KSValueParameter): String =
        parameter.type.resolve().toString()

    private fun reducerObjectsFiles(reducer: ReducerInfo, slots: List<ChildSlot>) =
        (listOf(reducer.declaration.containingFile) + slots.map { it.childReducer.declaration.containingFile })
            .filterNotNull()
            .distinct()
            .toTypedArray()
}

class SubScopeProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        SubScopeProcessor(environment.codeGenerator, environment.logger)
}
