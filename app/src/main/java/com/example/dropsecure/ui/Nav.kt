package com.example.dropsecure.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.dropsecure.container
import com.example.dropsecure.transport.TransportType

private fun transportOf(e: NavBackStackEntry): TransportType =
    runCatching { TransportType.valueOf(e.arguments?.getString("t") ?: "") }.getOrDefault(TransportType.WIFI_DIRECT)

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val draft = LocalContext.current.container().draft

    NavHost(navController = nav, startDestination = "splash") {
        composable("splash") {
            SplashScreen { nav.navigate("home") { popUpTo("splash") { inclusive = true } } }
        }
        composable("home") {
            HomeScreen(
                onSend = { draft.clear(); nav.navigate("send/${it.name}") },
                onReceive = { nav.navigate("receive/${it.name}") },
                onHistory = { nav.navigate("history") },
                onSettings = { nav.navigate("settings") },
                onActiveTransfers = { nav.navigate("flow") }
            )
        }
        composable("send/{t}") { e ->
            val t = transportOf(e)
            SendScreen(t, onBack = { nav.popBackStack() }, onFindDevices = { nav.navigate("discovery/${t.name}") })
        }
        composable("discovery/{t}") { e ->
            val t = transportOf(e)
            DiscoveryScreen(t, onBack = { nav.popBackStack() }, onStarted = { nav.navigate("flow") { popUpTo("home") } })
        }
        composable("flow") {
            FlowScreen(onHome = { nav.popBackStack("home", false) })
        }
        composable("receive/{t}") { e ->
            ReceiveScreen(transportOf(e), onBack = { nav.popBackStack() })
        }
        composable("history") {
            HistoryScreen(
                onBack = { nav.popBackStack() },
                onResume = { nav.navigate("discovery/${it.name}") },
                onReceive = { nav.navigate("receive/${it.name}") }
            )
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
