//
// ScopesExample.kt
// statoscope-kotlin
//
// Examples from Tutorial 05: Scopes
//
package statoscope.examples.tutorial05

// @extract:begin Scopes-Article-01
// @extract:begin Scopes-List-01
// @extract:begin Scopes-Root-01
import statoscope.core.Dependencies
import statoscope.core.EffectsState
import statoscope.core.ScopeConfig
import statoscope.core.SubScope
import statoscope.core.SuperstateKey
// @extract:end Scopes-Article-01
// @extract:end Scopes-List-01
// @extract:end Scopes-Root-01

// @extract:begin Scopes-ArticleTest-01
// @extract:begin Scopes-ListTest-01
// @extract:begin Scopes-RootTest-01
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import statoscope.testing.given
// @extract:end Scopes-ArticleTest-01
// @extract:end Scopes-ListTest-01
// @extract:end Scopes-RootTest-01

// @extract:begin Scopes-IntegrationTest-01
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import statoscope.core.createScope
// @extract:end Scopes-IntegrationTest-01

// @extract:begin Scopes-Article-01
/**
 * `NewsFeedListSuperstateKey` lives here, ahead of [NewsFeedListState] itself (declared below),
 * because [newsFeedArticleUpdate] needs to name it — Kotlin doesn't require top-level
 * declarations in one file to appear in dependency order, only *this page* reads top to bottom.
 */
val NewsFeedListSuperstateKey = SuperstateKey<NewsFeedListState>("NewsFeedList")

/** How many articles can be favorited at once — enforced where the button lives, see below. */
const val MAX_FAVORITES = 3

/**
 * The leaf. Two kinds of `When` live here, and they're handled completely differently:
 * [NewsFeedArticleWhen.UserToggledExpanded] is genuinely local — nothing outside this one
 * article cares whether its body is expanded — while
 * [NewsFeedArticleWhen.UserTappedFavoriteButton] only *reports* a tap; favoriting itself is
 * [NewsFeedListState]'s concern (see its `onChildAction` below), since the favorited set has to
 * be shared across every article, not duplicated into each one's own state.
 */
data class NewsFeedArticleState(
    val id: String,
    val title: String,
    val content: String,
    val isExpanded: Boolean = false,
    val favoritesLimitReached: Boolean = false
)

sealed interface NewsFeedArticleWhen {
    data object UserToggledExpanded : NewsFeedArticleWhen
    data object UserTappedFavoriteButton : NewsFeedArticleWhen
}

fun newsFeedArticleUpdate(
    state: NewsFeedArticleState,
    action: NewsFeedArticleWhen,
    effects: EffectsState<NewsFeedArticleWhen>,
    dependencies: Dependencies
): NewsFeedArticleState = when (action) {
    is NewsFeedArticleWhen.UserToggledExpanded ->
        state.copy(isExpanded = !state.isExpanded)

    is NewsFeedArticleWhen.UserTappedFavoriteButton -> {
        // A live read of the list's own state — resolved fresh on every call, never a value
        // copied in ahead of time. Only used to decide whether tapping is even allowed; the
        // actual favoriting happens one level up (NewsFeedListState.onChildAction, below).
        val list = dependencies.resolve(NewsFeedListSuperstateKey)
        val alreadyFavorited = list.favorites.any { it.id == state.id }
        state.copy(favoritesLimitReached = !alreadyFavorited && list.favorites.size >= MAX_FAVORITES)
    }
}
// @extract:end Scopes-Article-01

// @extract:begin Scopes-List-01
data class Article(val id: String, val title: String, val content: String)
data class Favorite(val id: String, val order: Int)

/**
 * The middle level — both a child (of [NewsFeedState]) and a parent (of [NewsFeedArticleState])
 * at once, exactly like a real screen sitting between a feature root and a detail view. Owns
 * `favorites` as the one place it's ever stored — nothing below duplicates it, which is the
 * whole reason [NewsFeedArticleState] has to read it live instead of keeping its own copy.
 */
data class NewsFeedListState(
    val articles: List<Article> = emptyList(),
    val favorites: List<Favorite> = emptyList(),
    val selectedArticle: NewsFeedArticleState? = null
)

sealed interface NewsFeedListWhen {
    data class UserSelectedArticle(val id: String) : NewsFeedListWhen
    data object UserDismissedArticle : NewsFeedListWhen
    data class ToggledFavorite(val id: String) : NewsFeedListWhen
}

fun newsFeedListUpdate(
    state: NewsFeedListState,
    action: NewsFeedListWhen,
    effects: EffectsState<NewsFeedListWhen>,
    dependencies: Dependencies
): NewsFeedListState = when (action) {
    is NewsFeedListWhen.UserSelectedArticle -> {
        val article = state.articles.first { it.id == action.id }
        state.copy(
            selectedArticle = NewsFeedArticleState(id = article.id, title = article.title, content = article.content)
        )
    }

    is NewsFeedListWhen.UserDismissedArticle ->
        state.copy(selectedArticle = null)

    is NewsFeedListWhen.ToggledFavorite -> {
        val alreadyFavorited = state.favorites.any { it.id == action.id }
        val newFavorites = if (alreadyFavorited) {
            state.favorites.filterNot { it.id == action.id }
        } else {
            state.favorites + Favorite(id = action.id, order = state.favorites.size)
        }
        state.copy(favorites = newFavorites)
    }
}

/**
 * Composes [NewsFeedArticleState] as this scope's own child. `onChildAction` only reacts to
 * [NewsFeedArticleWhen.UserTappedFavoriteButton] — every other article `When` (including the
 * purely local `UserToggledExpanded`) reaches this closure too (nothing is filtered out before
 * it runs — see [SubScope]'s doc), it just returns `null` for them. `childState` here is already
 * the article's *new* state, computed by [newsFeedArticleUpdate] above — that's what makes
 * reading `childState.favoritesLimitReached` here safe rather than one step stale.
 */
val newsFeedArticleSubScope = SubScope<NewsFeedListState, NewsFeedListWhen, NewsFeedArticleState, NewsFeedArticleWhen>(
    config = ScopeConfig(
        // Never actually used — activation always overrides it with the value this scope's own
        // update() just wrote to `selectedArticle`. See SubScope.config's doc.
        initial = NewsFeedArticleState(id = "", title = "", content = ""),
        update = ::newsFeedArticleUpdate
    ),
    get = { it.selectedArticle },
    set = { state, child -> state.copy(selectedArticle = child) },
    onChildAction = { childWhen, childState, _ ->
        if (childWhen is NewsFeedArticleWhen.UserTappedFavoriteButton && !childState.favoritesLimitReached) {
            NewsFeedListWhen.ToggledFavorite(childState.id)
        } else {
            null
        }
    },
    superstateKey = NewsFeedListSuperstateKey
)

val newsFeedListConfig = ScopeConfig(
    initial = NewsFeedListState(),
    update = ::newsFeedListUpdate,
    subScopes = listOf(newsFeedArticleSubScope)
)
// @extract:end Scopes-List-01

// @extract:begin Scopes-Root-01
/**
 * The root — owns nothing but the feature toggle itself. Nothing about favorites, articles, or
 * the currently selected one belongs here; this level's only job is deciding whether
 * [NewsFeedListState] exists at all.
 */
data class NewsFeedState(
    val featureEnabled: Boolean = false,
    val list: NewsFeedListState? = null
)

sealed interface NewsFeedWhen {
    data object UserEnabledFeature : NewsFeedWhen
    data object UserDisabledFeature : NewsFeedWhen
}

fun newsFeedUpdate(
    state: NewsFeedState,
    action: NewsFeedWhen,
    effects: EffectsState<NewsFeedWhen>,
    dependencies: Dependencies
): NewsFeedState = when (action) {
    is NewsFeedWhen.UserEnabledFeature -> state.copy(
        featureEnabled = true,
        list = NewsFeedListState(
            articles = listOf(
                Article(id = "1", title = "Kotlin Coroutines 101", content = "..."),
                Article(id = "2", title = "Composing Statoscope Scopes", content = "...")
            )
        )
    )

    is NewsFeedWhen.UserDisabledFeature ->
        state.copy(featureEnabled = false, list = null)
}

/**
 * No `onChildAction`, no `superstateKey` — composition doesn't require either; only declare them
 * when there's an actual need. The root has none: it never reacts to anything the list does, and
 * the list never needs to read anything of the root's.
 */
val newsFeedListSubScope = SubScope<NewsFeedState, NewsFeedWhen, NewsFeedListState, NewsFeedListWhen>(
    config = newsFeedListConfig,
    get = { it.list },
    set = { state, child -> state.copy(list = child) }
)

val newsFeedConfig = ScopeConfig(
    initial = NewsFeedState(),
    update = ::newsFeedUpdate,
    subScopes = listOf(newsFeedListSubScope)
)
// @extract:end Scopes-Root-01

// @extract:begin Scopes-ArticleTest-01
class NewsFeedArticleTest {

    @Test
    fun testTogglingExpandedIsPurelyLocal() {
        given(ScopeConfig(initial = NewsFeedArticleState(id = "1", title = "T", content = "C"), update = ::newsFeedArticleUpdate))
            .on(NewsFeedArticleWhen.UserToggledExpanded)
            .thenEquals(NewsFeedArticleState::isExpanded, true)
            .on(NewsFeedArticleWhen.UserToggledExpanded)
            .thenEquals(NewsFeedArticleState::isExpanded, false)
            .runTest()
    }

    @Test
    fun testFavoritingIsBlockedOnlyAtTheLimit() {
        val nearLimit = NewsFeedListState(favorites = listOf(Favorite("a", 0), Favorite("b", 1), Favorite("c", 2)))

        given(ScopeConfig(initial = NewsFeedArticleState(id = "d", title = "T", content = "C"), update = ::newsFeedArticleUpdate))
            .withSuperstate(NewsFeedListSuperstateKey, nearLimit)
            .on(NewsFeedArticleWhen.UserTappedFavoriteButton)
            .thenEquals(NewsFeedArticleState::favoritesLimitReached, true)
            .runTest()
    }

    @Test
    fun testAnAlreadyFavoritedArticleCanAlwaysBeUnfavorited() {
        val atLimitIncludingThisOne = NewsFeedListState(favorites = listOf(Favorite("a", 0), Favorite("b", 1), Favorite("d", 2)))

        given(ScopeConfig(initial = NewsFeedArticleState(id = "d", title = "T", content = "C"), update = ::newsFeedArticleUpdate))
            .withSuperstate(NewsFeedListSuperstateKey, atLimitIncludingThisOne)
            .on(NewsFeedArticleWhen.UserTappedFavoriteButton)
            .thenEquals(NewsFeedArticleState::favoritesLimitReached, false)
            .runTest()
    }
}
// @extract:end Scopes-ArticleTest-01

// @extract:begin Scopes-ListTest-01
class NewsFeedListTest {

    private val twoArticles = listOf(Article("1", "One", "..."), Article("2", "Two", "..."))

    @Test
    fun testSelectingAndDismissingAnArticle() {
        given(ScopeConfig(initial = NewsFeedListState(articles = twoArticles), update = ::newsFeedListUpdate))
            .on(NewsFeedListWhen.UserSelectedArticle("1"))
            .then { state -> assertEquals("One", state.selectedArticle?.title) }
            .on(NewsFeedListWhen.UserDismissedArticle)
            .then { state -> assertNull(state.selectedArticle) }
            .runTest()
    }

    @Test
    fun testTogglingAFavorite() {
        given(ScopeConfig(initial = NewsFeedListState(articles = twoArticles), update = ::newsFeedListUpdate))
            .on(NewsFeedListWhen.ToggledFavorite("1"))
            .thenEquals(NewsFeedListState::favorites, listOf(Favorite("1", 0)))
            .on(NewsFeedListWhen.ToggledFavorite("1"))
            .thenEquals(NewsFeedListState::favorites, emptyList())
            .runTest()
    }
}
// @extract:end Scopes-ListTest-01

// @extract:begin Scopes-RootTest-01
class NewsFeedTest {

    @Test
    fun testEnablingTheFeatureCreatesTheList() {
        given(ScopeConfig(initial = NewsFeedState(), update = ::newsFeedUpdate))
            .on(NewsFeedWhen.UserEnabledFeature)
            .then { state -> assertEquals(2, state.list?.articles?.size) }
            .on(NewsFeedWhen.UserDisabledFeature)
            .then { state -> assertNull(state.list) }
            .runTest()
    }
}
// @extract:end Scopes-RootTest-01

// @extract:begin Scopes-IntegrationTest-01
/**
 * Everything above tests one reducer at a time — deliberately: [statoscope.testing.TestPlan]
 * runs `update` directly, so it has no composed tree to navigate, only ever the one scope it was
 * given (see [statoscope.testing.TestPlan.withSuperstate]'s own doc). Proving the *composition*
 * actually works — that tapping favorite on a real, nested article scope really does reach the
 * list two levels up — needs a real [statoscope.core.ScopeStore], built with [createScope],
 * exactly what's done here by hand. [statoscope.testing.givenStore] (`StoreTestPlan.kt`) now
 * wraps this same shape in a fluent `with()`/`pop()` builder — prefer it for new composed-tree
 * tests; this one stays as Tutorial 05's own worked example of the lower-level primitives it's
 * built from.
 */
class ScopesIntegrationTest {

    @Test
    fun testFavoritingAnArticleReachesTheListWithNoRelayCodeInAnyReducer() {
        val store = createScope(newsFeedConfig)
        store.send(NewsFeedWhen.UserEnabledFeature)

        val listStore = store.childStore(newsFeedListSubScope)!!
        listStore.send(NewsFeedListWhen.UserSelectedArticle("1"))

        val articleStore = listStore.childStore(newsFeedArticleSubScope)!!
        articleStore.send(NewsFeedArticleWhen.UserTappedFavoriteButton)

        // Reached through two levels of composition — nothing here ever called
        // store.send(NewsFeedWhen...) or listStore.send(NewsFeedListWhen.ToggledFavorite(...))
        // directly; onChildAction did that automatically, synchronously, on both hops.
        assertEquals(listOf(Favorite("1", 0)), store.state.list?.favorites)
        assertTrue(articleStore.state.favoritesLimitReached == false)
    }
}
// @extract:end Scopes-IntegrationTest-01
