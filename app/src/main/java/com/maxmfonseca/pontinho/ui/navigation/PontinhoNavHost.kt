package com.maxmfonseca.pontinho.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import java.time.LocalDate

@Composable
fun PontinhoNavHost(
    container: AppContainer,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    startDestination: String = Destination.Home.route,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = { ExitTransition.None },
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
                initializer { MatchViewModel(matchId, container.matchRepository) }
            })
            val state by matchViewModel.uiState.collectAsStateWithLifecycle()
            LaunchedEffect(matchViewModel) {
                matchViewModel.events.collect { event ->
                    when (event) {
                        is MatchEvent.OpenResults -> navController.navigate(Destination.Results.createRoute(event.matchId.value)) {
                            launchSingleTop = true
                        }
                    }
                }
            }
            MatchScreen(state, { navController.popBackStack() }, matchViewModel::addScore,
                matchViewModel::selectIndicator, matchViewModel::confirmIndicator, matchViewModel::dismissDialog,
                matchViewModel::updateScore, matchViewModel::confirmScore, matchViewModel::wentOut, matchViewModel::viewResults,
                onAddPlayer = matchViewModel::openAddPlayer, onPlayerNameChange = matchViewModel::updatePlayerName,
                onConfirmPlayerName = matchViewModel::confirmPlayerName, onMovePlayer = matchViewModel::movePlayer,
                onEditScore = matchViewModel::editScore, onDeleteScore = matchViewModel::deleteScore)
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
                onReducedMotionSelected = settingsViewModel::setReducedMotion,
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
            val version = remember(context) {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
            }
            AboutScreen(versionName = version, onBack = { navController.popBackStack() })
        }
    }
}

private fun settingsFactory(container: AppContainer, context: Context) = viewModelFactory {
    initializer {
        SettingsViewModel(
            container.preferencesRepository,
            AndroidXAppLocaleController(context.applicationContext),
        )
    }
}
