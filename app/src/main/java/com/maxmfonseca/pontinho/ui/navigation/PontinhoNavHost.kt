package com.maxmfonseca.pontinho.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.activity.compose.BackHandler
import androidx.compose.material3.CircularProgressIndicator
import androidx.navigation.NavBackStackEntry
import com.maxmfonseca.pontinho.domain.recognition.RecognitionAvailability
import com.maxmfonseca.pontinho.ui.about.CameraEducationScreen
import com.maxmfonseca.pontinho.ui.about.ScannerEntryRoute
import com.maxmfonseca.pontinho.ui.about.scannerEntryRoute
import com.maxmfonseca.pontinho.ui.scanner.*
import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.ui.home.HomeScreen
import com.maxmfonseca.pontinho.ui.home.HomeViewModel
import com.maxmfonseca.pontinho.ui.learn.LearnScreen
import com.maxmfonseca.pontinho.ui.about.AboutScreen
import com.maxmfonseca.pontinho.ui.settings.AndroidXAppLocaleController
import com.maxmfonseca.pontinho.ui.settings.AppearanceScreen
import com.maxmfonseca.pontinho.ui.settings.LanguageScreen
import com.maxmfonseca.pontinho.ui.settings.SettingsScreen
import com.maxmfonseca.pontinho.ui.settings.SettingsViewModel
import com.maxmfonseca.pontinho.ui.setup.MatchSetupEvent
import com.maxmfonseca.pontinho.ui.setup.MatchSetupScreen
import com.maxmfonseca.pontinho.ui.setup.MatchSetupViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.maxmfonseca.pontinho.domain.model.MatchId
import com.maxmfonseca.pontinho.ui.match.MatchViewModel
import com.maxmfonseca.pontinho.ui.match.MatchScreen
import com.maxmfonseca.pontinho.ui.match.MatchEvent
import com.maxmfonseca.pontinho.ui.history.HistoryScreen
import com.maxmfonseca.pontinho.ui.history.HistoryViewModel
import com.maxmfonseca.pontinho.ui.results.ResultsEvent
import com.maxmfonseca.pontinho.ui.results.ResultsScreen
import com.maxmfonseca.pontinho.ui.results.ResultsViewModel
import com.maxmfonseca.pontinho.ui.theme.LocalReducedMotion
import java.time.LocalDate
import kotlinx.coroutines.launch

@Composable
fun PontinhoNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Destination.Home.route,
) {
    val reducedMotion = LocalReducedMotion.current
    val navigationScope = rememberCoroutineScope()
    // Sequential fade-through: 75 ms out, then 75 ms in, in every direction.
    val enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(durationMillis = 75, delayMillis = 75))
    val exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(durationMillis = 75))
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface),
        enterTransition = { enter },
        exitTransition = { exit },
        popEnterTransition = { enter },
        popExitTransition = { exit },
        predictivePopEnterTransition = { enter },
        predictivePopExitTransition = { exit },
        sizeTransform = null,
    ) {
        composable(Destination.Home.route) {
            val homeViewModel: HomeViewModel = viewModel(
                factory = viewModelFactory {
                    initializer { HomeViewModel(container.matchRepository) }
                },
            )
            val state by homeViewModel.uiState.collectAsStateWithLifecycle()
            HomeScreen(
                state = state,
                onNewMatch = { navController.navigate(Destination.Setup.route) },
                onLearn = { navController.navigate(Destination.Learn.route) },
                onOpenDay = { navController.navigate(Destination.DayHistory.createRoute(it.toString())) },
                onSettings = { navController.navigate(Destination.Settings.route) },
            )
        }
        composable(Destination.Setup.route) {
            val setupViewModel: MatchSetupViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        MatchSetupViewModel(
                            savedStateHandle = createSavedStateHandle(),
                            matchRepository = container.matchRepository,
                        )
                    }
                },
            )
            val state by setupViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(setupViewModel) {
                setupViewModel.events.collect { event ->
                    when (event) {
                        MatchSetupEvent.Close -> navController.popBackStack()
                        is MatchSetupEvent.OpenMatch -> {
                            navController.navigate(Destination.Match.createRoute(event.matchId.value)) {
                                popUpTo(Destination.Setup.route) { inclusive = true }
                            }
                            setupViewModel.navigationHandled(event.matchId)
                        }
                    }
                }
            }
            MatchSetupScreen(
                state = state,
                onBack = setupViewModel::cancel,
                onAddPlayer = setupViewModel::openAddPlayer,
                onEditPlayer = setupViewModel::openEditPlayer,
                onRemovePlayer = setupViewModel::removePlayer,
                onMovePlayer = setupViewModel::movePlayer,
                onSelectInitialDealer = setupViewModel::selectInitialDealer,
                onReorderPlayers = setupViewModel::reorderPlayers,
                onUseLastMatch = setupViewModel::requestUseLastMatch,
                onDismissReplace = setupViewModel::dismissUseLastMatchConfirmation,
                onConfirmReplace = setupViewModel::confirmUseLastMatch,
                onDialogNameChange = setupViewModel::updateDialogName,
                onDismissName = setupViewModel::dismissPlayerName,
                onConfirmName = setupViewModel::confirmPlayerName,
                onCreateMatch = setupViewModel::createMatch,
            )
        }
        composable(Destination.Match.route, arguments = listOf(navArgument(Destination.Match.MATCH_ID) { type = NavType.StringType })) { entry ->
            // Navigation decodes the URI segment once. Decoding again would corrupt
            // literal percent escapes and plus signs in a valid match identity.
            val matchId = MatchId(requireNotNull(entry.arguments?.getString(Destination.Match.MATCH_ID)))
            val matchViewModel: MatchViewModel = viewModel(factory = viewModelFactory {
                initializer { MatchViewModel(matchId, container.matchRepository,
                    savedStateHandle = createSavedStateHandle(),
                    recognitionAvailable = { container.cardRecognitionEngineProvider.get().availability is RecognitionAvailability.Available }) }
            })
            val state by matchViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(matchViewModel) {
                matchViewModel.events.collect { event ->
                    when (event) {
                        is MatchEvent.OpenScanner -> {
                            val request = matchViewModel.validateScanner(ScannerContext.from(event.request))
                            if (request != null && navController.currentBackStackEntry == entry) {
                                navController.navigate(Destination.Scanner.createRoute(request)) { launchSingleTop = true }
                            } else matchViewModel.scannerCancelled(event.request.submissionToken)
                        }
                        is MatchEvent.OpenResults -> navController.navigate(Destination.Results.createRoute(event.matchId.value)) {
                            launchSingleTop = true
                        }
                    }
                }
            }
            MatchScreen(state, { navController.popBackStack() }, matchViewModel::addScore,
                matchViewModel::selectIndicatorRank, matchViewModel::confirmIndicator, matchViewModel::dismissDialog,
                matchViewModel::updateScore, matchViewModel::confirmScore, matchViewModel::wentOut, matchViewModel::viewResults,
                onAddPlayer = matchViewModel::openAddPlayer, onPlayerNameChange = matchViewModel::updatePlayerName,
                onConfirmPlayerName = matchViewModel::confirmPlayerName, onReorderPlayers = matchViewModel::reorderPlayers,
                onEditScore = matchViewModel::editScore, onDeleteScore = matchViewModel::deleteScore,
                onSelectIndicatorColor = matchViewModel::selectIndicatorColor,
                onCamera = matchViewModel::openScanner)
        }
        composable(Destination.Scanner.route, arguments = Destination.Scanner.argumentNames.map { name ->
            navArgument(name) { type = NavType.StringType; if (name == "correction") { nullable = true; defaultValue = null } }
        }) { entry ->
            ScannerDestination(container, navController, entry, onEducationSeen = {
                // Dismissing education pops its entry; the preference write must outlive that entry.
                navigationScope.launch { container.preferencesRepository.setCameraEducationSeen(true) }
            })
        }
        composable(Destination.DayHistory.route, arguments = listOf(navArgument(Destination.DayHistory.LOCAL_DATE) { type = NavType.StringType })) { entry ->
            val localDate = LocalDate.parse(requireNotNull(entry.arguments?.getString(Destination.DayHistory.LOCAL_DATE)))
            val historyViewModel: HistoryViewModel = viewModel(factory = viewModelFactory {
                initializer { HistoryViewModel(localDate, container.matchRepository) }
            })
            val state by historyViewModel.uiState.collectAsStateWithLifecycle()
            HistoryScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSettings = { navController.navigate(Destination.Settings.route) },
                onNewMatch = { navController.navigate(Destination.Setup.route) },
                onOpenMatch = { navController.navigate(Destination.Match.createRoute(it.value)) },
                onToggleSummary = historyViewModel::toggleSummary,
                onRequestDelete = historyViewModel::requestDelete,
                onCancelDelete = historyViewModel::cancelDelete,
                onConfirmDelete = historyViewModel::confirmDelete,
                onClearDeleteError = historyViewModel::clearDeleteError,
            )
        }
        composable(Destination.Results.route, arguments = listOf(navArgument(Destination.Results.MATCH_ID) { type = NavType.StringType })) { entry ->
            val matchId = MatchId(requireNotNull(entry.arguments?.getString(Destination.Results.MATCH_ID)))
            val resultsViewModel: ResultsViewModel = viewModel(factory = viewModelFactory {
                initializer { ResultsViewModel(matchId, container.matchRepository) }
            })
            val state by resultsViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(resultsViewModel) {
                resultsViewModel.events.collect { event ->
                    when (event) {
                        ResultsEvent.NewMatch -> navController.navigate(Destination.Setup.route) {
                            popUpTo(Destination.Home.route)
                        }
                        is ResultsEvent.OpenMatch -> if (!navController.popBackStack()) {
                            navController.navigate(Destination.Match.createRoute(event.matchId.value))
                        }
                        is ResultsEvent.OpenHistory -> {
                            val historyRoute = Destination.DayHistory.createRoute(event.localDate.toString())
                            if (!navController.popBackStack(historyRoute, inclusive = false)) {
                                navController.navigate(historyRoute) { popUpTo(Destination.Home.route) }
                            }
                        }
                    }
                }
            }
            ResultsScreen(state, resultsViewModel::newMatch, resultsViewModel::openMatch, resultsViewModel::openHistory)
        }
        composable(Destination.Settings.route) {
            SettingsScreen(
                onBack = { navController.popBackStack() },
                onAppearance = { navController.navigate(Destination.Appearance.route) },
                onLanguage = { navController.navigate(Destination.Language.route) },
                onLearn = { navController.navigate(Destination.Learn.route) },
                onAbout = { navController.navigate(Destination.About.route) },
            )
        }
        composable(Destination.Appearance.route) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory(container, context))
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            AppearanceScreen(
                preferences = settingsState.preferences,
                onBack = { navController.popBackStack() },
                onThemeSelected = settingsViewModel::setTheme,
            )
        }
        composable(Destination.Language.route) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory(container, context))
            val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
            LanguageScreen(
                selected = settingsState.preferences.language,
                onBack = { navController.popBackStack() },
                onLanguageSelected = settingsViewModel::setLanguage,
            )
        }
        composable(Destination.Learn.route) { LearnScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.About.route) {
            val context = androidx.compose.ui.platform.LocalContext.current
            val scope = rememberCoroutineScope()
            val version = remember(context) {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }
            AboutScreen(versionName = version, onBack = { navController.popBackStack() },
                onLearn = { navController.navigate(Destination.Learn.route) },
                onCameraEducationSeen = {
                    scope.launch { container.preferencesRepository.setCameraEducationSeen(true) }
                })
        }
    }
}

/** Sole navigation owner for scanner completion; the device screen only emits events. */
@Composable
private fun ScannerDestination(container: AppContainer, nav: NavHostController, entry: NavBackStackEntry,
    onEducationSeen: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val route = remember(entry) { ScannerContext.decode { entry.arguments?.getString(it) } }
    val parent = remember(entry) { nav.previousBackStackEntry?.takeIf { it.destination.route == Destination.Match.route } }
    if (route == null || parent == null || parent.arguments?.getString(Destination.Match.MATCH_ID) != route.match) {
        LaunchedEffect(entry) { if (nav.currentBackStackEntry == entry) nav.popBackStack() }
        return
    }
    val matchViewModel: MatchViewModel = viewModel(viewModelStoreOwner = parent, factory = viewModelFactory {
        initializer { MatchViewModel(MatchId(route.match), container.matchRepository,
            savedStateHandle = createSavedStateHandle(),
            recognitionAvailable = { container.cardRecognitionEngineProvider.get().availability is RecognitionAvailability.Available }) }
    })
    var request by remember(entry) { mutableStateOf<ScanRequest?>(null) }
    var education by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var captureReady by remember(entry) { mutableStateOf(false) }
    var scannerStarted by rememberSaveable { mutableStateOf(false) }
    var handled by rememberSaveable { mutableStateOf(false) }
    fun cancel() {
        if (handled) return
        handled = true
        matchViewModel.scannerCancelled(route.token)
        if (nav.currentBackStackEntry == entry) nav.popBackStack()
    }
    BackHandler { cancel() }
    LaunchedEffect(entry) {
        matchViewModel.uiState.first { !it.isLoading }
        // Restoration may follow a committed write; the stable request is required for receipt replay.
        val validated = matchViewModel.validateScanner(route, allowCommitted = scannerStarted)
        if (validated == null) { cancel(); return@LaunchedEffect }
        request = validated
        if (education == null) education = scannerEntryRoute(
            container.preferencesRepository.preferences.first().cameraEducationSeen) == ScannerEntryRoute.Education
    }
    if (request == null || education == null) { CircularProgressIndicator(); return }
    if (education == true) {
        CameraEducationScreen(
            onEducationSeen = onEducationSeen,
            onContinue = { education = false }, onDismiss = ::cancel,
        )
        return
    }
    LaunchedEffect(entry) {
        val validated = matchViewModel.validateScanner(route, allowCommitted = scannerStarted)
        if (validated == null) cancel()
        else { request = validated; scannerStarted = true; captureReady = true }
    }
    if (!captureReady) { CircularProgressIndicator(); return }
    val engine = remember(container) { container.cardRecognitionEngineProvider.get() }
    val scanner: ScannerViewModel = viewModel(factory = viewModelFactory {
        initializer { ScannerViewModel(requireNotNull(request), createSavedStateHandle(), engine,
            container.matchRepository, ScannerImageLoader(context.contentResolver)::load) }
    })
    LaunchedEffect(scanner) {
        scanner.events.collect { event ->
            if (!handled) when (event) {
                ScannerEvent.Cancelled -> cancel()
                is ScannerEvent.Saved -> {
                    if (event.matchId == request?.matchId && event.roundId == request?.roundId &&
                        event.participantId == request?.participantId) {
                        // Repository observation, not an inference result, closes the manual dialog.
                        matchViewModel.scannerSaved(route.token, scanner.uiState.value.total)
                        handled = true
                        if (nav.currentBackStackEntry == entry) nav.popBackStack()
                    }
                }
            }
        }
    }
    ScannerScreen(scanner, engine.availability is RecognitionAvailability.Available)
}

private fun settingsFactory(container: AppContainer, context: Context) = viewModelFactory {
    initializer {
        SettingsViewModel(
            container.preferencesRepository,
            AndroidXAppLocaleController(context.applicationContext),
        )
    }
}
