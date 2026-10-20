package top.steins.autologin

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import top.steins.autologin.navigation.AppDestination
import top.steins.autologin.navigation.appComposable
import top.steins.autologin.ui.component.CapsuleToast
import top.steins.autologin.ui.component.rememberCapsuleToastState
import top.steins.autologin.ui.route.AboutRoute
import top.steins.autologin.ui.route.AccountRoute
import top.steins.autologin.ui.route.AppDialogs
import top.steins.autologin.ui.route.HomeRoute
import top.steins.autologin.ui.route.DeviceLogoutMessageEffect
import top.steins.autologin.ui.route.LocationPermissionEffect
import top.steins.autologin.ui.route.LoginMessageEffect
import top.steins.autologin.ui.route.LogRoute
import top.steins.autologin.ui.route.SoftwareSettingsRoute
import top.steins.autologin.ui.route.SettingsRoute
import top.steins.autologin.ui.route.UpdateMessageEffect
import top.steins.autologin.ui.route.WifiConfigRoute
import top.steins.autologin.ui.route.SeamlessLoginRoute

private val NavigationAnimationSpec = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f,
    visibilityThreshold = IntOffset.VisibilityThreshold
)

private val NavigationDimmingAnimationSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = 800f
)

private const val CoveredPageAlpha = 0.72f

/** 应用壳层只负责导航和全局覆盖层；页面状态由各 Route 就近订阅。 */
@Composable
fun AppRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val toastState = rememberCapsuleToastState()

    fun navigateTo(destination: AppDestination) {
        navController.navigate(destination.route) {
            launchSingleTop = true
        }
    }

    LocationPermissionEffect(viewModel.session)
    UpdateMessageEffect(viewModel.update, toastState)
    LoginMessageEffect(viewModel.session, toastState)
    DeviceLogoutMessageEffect(viewModel.session, toastState)

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = AppDestination.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            enterTransition = {
                slideInHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    initialOffsetX = { it }
                )
            },
            exitTransition = {
                slideOutHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    targetOffsetX = { -it / 3 }
                ) + fadeOut(
                    animationSpec = NavigationDimmingAnimationSpec,
                    targetAlpha = CoveredPageAlpha
                )
            },
            popEnterTransition = {
                slideInHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    initialOffsetX = { -it / 3 }
                ) + fadeIn(
                    animationSpec = NavigationDimmingAnimationSpec,
                    initialAlpha = CoveredPageAlpha
                )
            },
            popExitTransition = {
                slideOutHorizontally(
                    animationSpec = NavigationAnimationSpec,
                    targetOffsetX = { it }
                )
            }
        ) {
            appComposable(AppDestination.Home, navController) {
                HomeRoute(
                    stateHolder = viewModel.session,
                    toastState = toastState,
                    onNavigateToAccount = { navigateTo(AppDestination.Account) },
                    onNavigateToSettings = { navigateTo(AppDestination.Settings) },
                    onNavigateToSeamless = { navigateTo(AppDestination.SeamlessLogin) }
                )
            }

            appComposable(AppDestination.Account, navController) { onNavigateBack ->
                AccountRoute(
                    stateHolder = viewModel.accounts,
                    toastState = toastState,
                    onNavigateBack = onNavigateBack
                )
            }

            appComposable(AppDestination.SeamlessLogin, navController) { onNavigateBack ->
                SeamlessLoginRoute(viewModel.session, onNavigateBack)
            }

            appComposable(AppDestination.Settings, navController) { onNavigateBack ->
                SettingsRoute(
                    stateHolder = viewModel.preferences,
                    logs = viewModel.logs,
                    onNavigateBack = onNavigateBack,
                    onNavigateToLog = { navigateTo(AppDestination.Log) },
                    onNavigateToWifiConfig = { navigateTo(AppDestination.WifiConfig) },
                    onNavigateToSoftwareSettings = { navigateTo(AppDestination.SoftwareSettings) },
                    onNavigateToAbout = { navigateTo(AppDestination.About) }
                )
            }

            appComposable(AppDestination.SoftwareSettings, navController) { onNavigateBack ->
                SoftwareSettingsRoute(stateHolder = viewModel.preferences, onNavigateBack = onNavigateBack)
            }

            appComposable(AppDestination.About, navController) { onNavigateBack ->
                AboutRoute(
                    stateHolder = viewModel.update,
                    logs = viewModel.logs,
                    toastState = toastState,
                    onNavigateBack = onNavigateBack
                )
            }

            appComposable(AppDestination.Log, navController) { onNavigateBack ->
                LogRoute(stateHolder = viewModel.logs, onNavigateBack = onNavigateBack)
            }

            appComposable(AppDestination.WifiConfig, navController) { onNavigateBack ->
                WifiConfigRoute(
                    stateHolder = viewModel.preferences,
                    scan = viewModel.wifiScan,
                    toastState = toastState,
                    onNavigateBack = onNavigateBack
                )
            }
        }

        CapsuleToast(
            state = toastState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    AppDialogs(
        updates = viewModel.update,
        accounts = viewModel.accounts,
        onNavigateToAccount = { navigateTo(AppDestination.Account) }
    )
}
