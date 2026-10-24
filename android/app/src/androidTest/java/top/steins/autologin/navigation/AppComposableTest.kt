package top.steins.autologin.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import org.junit.Rule
import org.junit.Test

class AppComposableTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun onNavigateBack_whenCalledTwice_onlyPopsOwningEntry() {
        composeRule.setContent {
            val navController = rememberNavController()

            NavHost(
                navController = navController,
                startDestination = AppDestination.Home.route
            ) {
                appComposable(AppDestination.Home, navController) { _ ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        Button(
                            onClick = { navController.navigate(AppDestination.Account.route) },
                            modifier = Modifier.testTag(HOME_TAG)
                        ) {
                            Text("Home")
                        }
                    }
                }

                appComposable(AppDestination.Account, navController) { onNavigateBack ->
                    Button(
                        onClick = {
                            onNavigateBack()
                            onNavigateBack()
                        },
                        modifier = Modifier.testTag(BACK_TAG)
                    ) {
                        Text("Back twice")
                    }
                }
            }
        }

        composeRule.onNodeWithTag(HOME_TAG).performClick()
        composeRule.onNodeWithTag(BACK_TAG).performClick()

        composeRule.onNodeWithTag(HOME_TAG).assertIsDisplayed()
    }

    private companion object {
        const val HOME_TAG = "home"
        const val BACK_TAG = "back"
    }
}
