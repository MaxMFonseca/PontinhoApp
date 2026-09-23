package com.maxmfonseca.pontinho.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.maxmfonseca.pontinho.R
import com.maxmfonseca.pontinho.di.AppContainer
import com.maxmfonseca.pontinho.ui.home.HomeScreen
import com.maxmfonseca.pontinho.ui.home.HomeViewModel
import com.maxmfonseca.pontinho.ui.learn.LearnScreen
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
        composable(Destination.DayHistory.route) { RoutePlaceholder(R.string.route_day_history, "day_history_root") }
        composable(Destination.Results.route, arguments = listOf(navArgument(Destination.Results.MATCH_ID) { type = NavType.StringType })) {
            Column(Modifier.fillMaxSize().padding(24.dp).testTag("results_root"),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.route_results), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.match_finished), style = MaterialTheme.typography.headlineLarge)
                TextButton(onClick = { navController.popBackStack() }) { Text(stringResource(R.string.match_back)) }
            }
        }
        composable(Destination.Settings.route) { RoutePlaceholder(R.string.route_settings, "settings_root") }
        composable(Destination.Appearance.route) { RoutePlaceholder(R.string.route_appearance, "appearance_root") }
        composable(Destination.Language.route) { RoutePlaceholder(R.string.route_language, "language_root") }
        composable(Destination.Learn.route) { LearnScreen(onBack = { navController.popBackStack() }) }
        composable(Destination.About.route) { RoutePlaceholder(R.string.route_about, "about_root") }
    }
}

@Composable
private fun RoutePlaceholder(
    @StringRes title: Int,
    tag: String,
    @StringRes subtitle: Int = R.string.route_placeholder,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(title), style = MaterialTheme.typography.headlineLarge)
        Text(text = stringResource(subtitle), style = MaterialTheme.typography.bodyLarge)
    }
}
