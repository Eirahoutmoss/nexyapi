package com.eirahoutmoss.nexyapi.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.NexYapiApp
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.ui.screens.AcousticsScreen
import com.eirahoutmoss.nexyapi.ui.screens.CrackMeasureScreen
import com.eirahoutmoss.nexyapi.ui.screens.MotionScreen
import com.eirahoutmoss.nexyapi.ui.screens.CrackScreen
import com.eirahoutmoss.nexyapi.ui.screens.DrillScreen
import com.eirahoutmoss.nexyapi.ui.screens.SettingsScreen
import com.eirahoutmoss.nexyapi.ui.screens.VerifyScreen
import com.eirahoutmoss.nexyapi.ui.screens.VibrationScreen
import com.eirahoutmoss.nexyapi.ui.screens.StructureDetailScreen
import com.eirahoutmoss.nexyapi.ui.screens.StructureEditScreen
import com.eirahoutmoss.nexyapi.ui.screens.StructuresScreen

object Routes {
    const val STRUCTURES = "structures"
    const val NEW = "structure-new" // "structure/{id}" ile karışmasın diye ayrı biçim
    const val DETAIL = "structure/{id}"
    const val EDIT = "structure/{id}/edit"
    const val SETTINGS = "settings"
    const val Z2 = "structure/{id}/z2"
    const val V3 = "structure/{id}/v3"
    const val V3_MEASURE = "structure/{id}/v3/measure?file={file}"
    const val V11 = "structure/{id}/v11"
    const val W3 = "structure/{id}/w3"
    const val W1 = "structure/{id}/w1"
    const val VERIFY = "verify"

    fun detail(id: String) = "structure/$id"
    fun edit(id: String) = "structure/$id/edit"
    fun z2(id: String) = "structure/$id/z2"
    fun v3(id: String) = "structure/$id/v3"
    fun v11(id: String) = "structure/$id/v11"
    fun w3(id: String) = "structure/$id/w3"
    fun w1(id: String) = "structure/$id/w1"
    fun v3Measure(id: String, file: String) = "structure/$id/v3/measure?file=${Uri.encode(file)}"
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as NexYapiApp).container

@Composable
fun NexYapiNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.STRUCTURES) {
        composable(Routes.STRUCTURES) {
            StructuresScreen(
                onOpen = { nav.navigate(Routes.detail(it)) },
                onNew = { nav.navigate(Routes.NEW) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }
        composable(Routes.NEW) {
            StructureEditScreen(
                structureId = null,
                onDone = { id ->
                    nav.popBackStack()
                    if (id != null) nav.navigate(Routes.detail(id))
                },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.DETAIL, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            StructureDetailScreen(
                structureId = id,
                onEdit = { nav.navigate(Routes.edit(id)) },
                onOpenModule = { m ->
                    when (m) {
                        ModuleType.VIBRATION -> nav.navigate(Routes.z2(id))
                        ModuleType.CRACK -> nav.navigate(Routes.v3(id))
                        ModuleType.MOTION -> nav.navigate(Routes.v11(id))
                        ModuleType.ACOUSTICS -> nav.navigate(Routes.w3(id))
                        ModuleType.BEFORE_DRILL -> nav.navigate(Routes.w1(id))
                        else -> Unit
                    }
                },
                onDeleted = { nav.popBackStack(Routes.STRUCTURES, inclusive = false) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.EDIT, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            StructureEditScreen(
                structureId = id,
                onDone = { nav.popBackStack() },
                onBack = { nav.popBackStack() },
            )
        }
        composable(Routes.Z2, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            VibrationScreen(structureId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.V3, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            CrackScreen(
                structureId = id,
                onMeasure = { file -> nav.navigate(Routes.v3Measure(id, file)) },
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            Routes.V3_MEASURE,
            arguments = listOf(
                navArgument("id") { type = NavType.StringType },
                navArgument("file") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            CrackMeasureScreen(
                structureId = entry.arguments?.getString("id").orEmpty(),
                pendingRelative = entry.arguments?.getString("file").orEmpty(),
                onDone = { nav.popBackStack() },
            )
        }
        composable(Routes.V11, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            MotionScreen(structureId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.W3, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            AcousticsScreen(structureId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.W1, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            DrillScreen(structureId = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { nav.popBackStack() }, onVerify = { nav.navigate(Routes.VERIFY) })
        }
        composable(Routes.VERIFY) {
            VerifyScreen(onBack = { nav.popBackStack() })
        }
    }
}
