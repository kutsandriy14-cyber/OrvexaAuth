package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.*
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val accountViewModel: AccountViewModel = viewModel()
                val isAppLocked by accountViewModel.isAppLocked.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                        NavHost(
                            navController = navController,
                            startDestination = Screen.AccountChooser.route,
                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                        ) {
                            composable(Screen.AccountChooser.route) {
                                AccountChooserScreen(
                                    viewModel = accountViewModel,
                                    onNavigate = { screen -> navController.navigate(screen.route) }
                                )
                            }
                            composable(Screen.SignIn.route) {
                                SignInScreen(
                                    viewModel = accountViewModel,
                                    onNavigate = { screen -> navController.navigate(screen.route) },
                                    onLoginSuccess = {
                                        navController.navigate(Screen.Dashboard.route) {
                                            popUpTo(Screen.AccountChooser.route) { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable(Screen.RegisterName.route) {
                                RegisterNameScreen(
                                    viewModel = accountViewModel,
                                    onNavigate = { screen -> navController.navigate(screen.route) },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.RegisterBirthGender.route) {
                                RegisterBirthGenderScreen(
                                    viewModel = accountViewModel,
                                    onNavigate = { screen -> navController.navigate(screen.route) },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.RegisterPassword.route) {
                                RegisterPasswordScreen(
                                    viewModel = accountViewModel,
                                    onNavigate = { screen -> navController.navigate(screen.route) },
                                    onBack = { navController.popBackStack() }
                                )
                            }
                            composable(Screen.RegisterTerms.route) {
                                RegisterTermsScreen(
                                    viewModel = accountViewModel,
                                    onNavigate = { screen -> navController.navigate(screen.route) },
                                    onBack = { navController.popBackStack() },
                                    onRegisterSuccess = {
                                        navController.navigate(Screen.Dashboard.route) {
                                            popUpTo(Screen.AccountChooser.route) { inclusive = true }
                                        }
                                    }
                                )
                            }
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    viewModel = accountViewModel,
                                    onSignOut = {
                                        navController.navigate(Screen.AccountChooser.route) {
                                            popUpTo(0) { inclusive = true }
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (isAppLocked) {
                        PasscodeLockScreen(
                            viewModel = accountViewModel,
                            onUnlock = { accountViewModel.unlockApp() }
                        )
                    }
                }
            }
        }
    }
}
