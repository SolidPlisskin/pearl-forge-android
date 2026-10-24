package com.solidplisskin.pearlforge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.solidplisskin.pearlforge.AppContainer
import com.solidplisskin.pearlforge.audio.AudioPlayback
import com.solidplisskin.pearlforge.pipeline.PolisherProvider
import com.solidplisskin.pearlforge.settings.KeyStatus
import java.util.UUID
import kotlinx.coroutines.launch

enum class AppTab(val label: String, val icon: ImageVector) {
    CREATE("Create", Icons.Filled.Mic),
    HISTORY("History", Icons.Filled.History),
    READING("Reading", Icons.Filled.LibraryBooks),
    AI("AI", Icons.Filled.AutoAwesome),
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** Screens reached from History. */
sealed interface HistoryRoute {
    data class Recording(val id: String) : HistoryRoute
    data class PearlPage(val entryId: String, val pearlId: UUID) : HistoryRoute
    data class Topic(val name: String) : HistoryRoute
}

/** Screens reached from the AI tab. */
sealed interface AIRoute {
    data class Provider(val provider: PolisherProvider) : AIRoute
    data class Models(val provider: PolisherProvider, val forReading: Boolean) : AIRoute
    data class Key(val provider: PolisherProvider) : AIRoute
}

/**
 * The five tabs. On the tablet they sit in a rail down the left, and History shows the list and the
 * open recording side by side; on a phone-sized window, a bar along the bottom.
 */
@Composable
fun PearlForgeApp(container: AppContainer) {
    var tab by remember { mutableStateOf(AppTab.CREATE) }
    val history = remember { mutableStateListOf<HistoryRoute>() }
    val ai = remember { mutableStateListOf<AIRoute>() }
    var editingElevenLabsKey by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val historyPlayer = remember { AudioPlayback(context) }

    LaunchedEffect(Unit) {
        container.scope.launch { container.settings.checkSavedKeys() }
        container.scope.launch { container.polishers.checkSavedKeys() }
        container.library.refresh()
    }

    BackHandler(enabled = (tab == AppTab.HISTORY && history.isNotEmpty()) || (tab == AppTab.AI && ai.isNotEmpty()) ||
                    (tab == AppTab.SETTINGS && editingElevenLabsKey) || tab != AppTab.CREATE) {
        when {
            tab == AppTab.HISTORY && history.isNotEmpty() -> history.removeAt(history.lastIndex)
            tab == AppTab.AI && ai.isNotEmpty() -> ai.removeAt(ai.lastIndex)
            tab == AppTab.SETTINGS && editingElevenLabsKey -> editingElevenLabsKey = false
            else -> tab = AppTab.CREATE
        }
    }

    fun openTab(target: AppTab) { tab = target }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val twoPane = maxWidth >= 900.dp

        @Composable
        fun content() {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (tab) {
                    AppTab.CREATE -> CreateScreen(container, ::openTab)
                    AppTab.HISTORY -> HistoryTab(container, history, historyPlayer, twoPane, ::openTab)
                    AppTab.READING -> ReadingScreen(container)
                    AppTab.AI -> AITab(container, ai)
                    AppTab.SETTINGS -> if (editingElevenLabsKey) {
                        KeyEditor(container, KeyTarget(
                            title = "ElevenLabs key", keysPageLabel = "Get your key at elevenlabs.io",
                            keysPage = "https://elevenlabs.io/app/settings/api-keys", usesWorkspace = false,
                            isSaved = container.settings.elevenLabsStatus != KeyStatus.NotSet, workspace = "",
                            save = { key, _ -> if (key != null) container.settings.saveElevenLabsKey(key) },
                            remove = { container.settings.removeElevenLabsKey() },
                        )) { editingElevenLabsKey = false }
                    } else SettingsScreen(container) { editingElevenLabsKey = true }
                }
            }
        }

        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    AppTab.entries.forEach { item ->
                        NavigationRailItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(item.icon, null) }, label = { Text(item.label) })
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Scaffold(bottomBar = {
                NavigationBar {
                    AppTab.entries.forEach { item ->
                        NavigationBarItem(selected = tab == item, onClick = { tab = item }, icon = { Icon(item.icon, null) }, label = { Text(item.label) })
                    }
                }
            }) { padding -> Box(Modifier.fillMaxSize().padding(padding)) { content() } }
        }
    }
}

@Composable
private fun HistoryTab(container: AppContainer, stack: MutableList<HistoryRoute>, player: AudioPlayback, twoPane: Boolean, openTab: (AppTab) -> Unit) {
    fun open(route: HistoryRoute) {
        // In two panes, opening a recording from the list replaces what's on the right.
        if (twoPane && route is HistoryRoute.Recording && stack.none { it is HistoryRoute.Topic }) stack.clear()
        stack.add(route)
    }
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    @Composable
    fun page(route: HistoryRoute, showBack: Boolean) = when (route) {
        is HistoryRoute.Recording -> RecordingDetail(container, route.id, player, if (showBack) ::back else null, ::open)
        is HistoryRoute.PearlPage -> PearlPage(container, route.entryId, route.pearlId, player, ::back)
        is HistoryRoute.Topic -> TopicPage(container, route.name, ::back, ::open)
    }

    val selected = (stack.lastOrNull { it is HistoryRoute.Recording } as? HistoryRoute.Recording)?.id
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.width(400.dp).fillMaxHeight()) { HistoryList(container, selected, ::open, openTab) }
            VerticalDivider()
            Box(Modifier.weight(1f).fillMaxHeight()) {
                val top = stack.lastOrNull()
                if (top == null) EmptyState(Icons.Filled.TouchApp, "Choose a recording", "Pick one on the left to hear it, read its pearls and find reading.")
                else page(top, showBack = stack.size > 1)
            }
        }
    } else {
        val top = stack.lastOrNull()
        if (top == null) HistoryList(container, null, ::open, openTab) else page(top, showBack = true)
    }
}

@Composable
private fun AITab(container: AppContainer, stack: MutableList<AIRoute>) {
    val polishers = container.polishers
    fun back() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    when (val top = stack.lastOrNull()) {
        null -> AIHome(container) { stack.add(it) }
        is AIRoute.Provider -> ProviderPage(container, top.provider, ::back) { stack.add(it) }
        is AIRoute.Models -> ModelPicker(container, top.provider, top.forReading, ::back)
        is AIRoute.Key -> KeyEditor(container, KeyTarget(
            title = "${top.provider.displayName} key", keysPageLabel = top.provider.keysPageLabel, keysPage = top.provider.keysPage,
            usesWorkspace = top.provider == PolisherProvider.ANTHROPIC, isSaved = polishers.status[top.provider] != KeyStatus.NotSet,
            workspace = if (top.provider == PolisherProvider.ANTHROPIC) polishers.workspaceId() else "",
            save = { key, workspace -> polishers.saveKey(key, workspace, top.provider) },
            remove = { polishers.removeKey(top.provider) },
        ), ::back)
    }
}
