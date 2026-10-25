package com.eirahoutmoss.nexyapi.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.CardDefaults
import com.eirahoutmoss.nexyapi.ui.components.FileImage
import com.eirahoutmoss.nexyapi.ui.theme.accent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Apartment
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.core.license.LicenseCheck
import com.eirahoutmoss.nexyapi.core.model.ModuleType
import com.eirahoutmoss.nexyapi.data.StructureSummary
import com.eirahoutmoss.nexyapi.ui.appContainer
import com.eirahoutmoss.nexyapi.ui.components.LicenseDialog
import com.eirahoutmoss.nexyapi.ui.components.formatDate
import com.eirahoutmoss.nexyapi.ui.components.icon
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class StructuresViewModel(private val c: AppContainer) : ViewModel() {
    /** null = henüz yükleniyor. */
    val items: StateFlow<List<StructureSummary>?> = c.structures.observeSummaries()
        .map<List<StructureSummary>, List<StructureSummary>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val license = c.licenses.state

    suspend fun canAdd(): Boolean = c.licenses.state.value.plan.canAddStructure(c.structures.count())

    fun activate(token: String): String? = when (val r = c.licenses.activate(token)) {
        is LicenseCheck.Valid -> null
        is LicenseCheck.Expired -> "Bu lisansın süresi ${r.license.expires} tarihinde dolmuş."
        is LicenseCheck.Invalid -> r.reason
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructuresScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onSettings: () -> Unit) {
    val container = appContainer()
    val vm: StructuresViewModel = viewModel(factory = viewModelFactory { initializer { StructuresViewModel(container) } })
    val items by vm.items.collectAsStateWithLifecycle()
    val license by vm.license.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showLimit by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Yapılarım")
                        Text(license.holderLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, contentDescription = "Ayarlar ve güncelleme") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { scope.launch { if (vm.canAdd()) onNew() else showLimit = true } },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Yeni Yapı") },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )
        },
    ) { padding ->
        val list = items
        when {
            list == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            list.isEmpty() -> EmptyState(Modifier.padding(padding))
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(list, key = { it.structure.id }) { s -> StructureCard(s) { onOpen(s.structure.id) } }
            }
        }
    }

    if (showLimit) {
        LicenseDialog(
            title = "Ücretsiz sürüm sınırı",
            explanation = "Ücretsiz sürümde tek yapı kaydedilebilir. Sınırsız yapı ve büro raporu için Profesyonel lisans anahtarınızı girin.",
            onSubmit = { token -> vm.activate(token).also { if (it == null) { showLimit = false; onNew() } } },
            onDismiss = { showLimit = false },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Outlined.Apartment, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.size(16.dp))
        Text("Henüz kayıtlı yapı yok", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            "Evinizi, dairenizi ya da ofisinizi ekleyin. Ölçümler o yapının karnesinde birikir.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StructureCard(s: StructureSummary, onClick: () -> Unit) {
    val c = appContainer()
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            FileImage(
                file = s.structure.photoFile?.let { c.structures.resolve(it) },
                maxDimension = 320,
                modifier = Modifier.size(84.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Icon(Icons.Outlined.Apartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
            }
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(s.structure.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val sub = listOfNotNull(
                    s.structure.floor.takeIf { it.isNotBlank() }?.let { "Kat $it" },
                    s.structure.address.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (sub.isNotEmpty()) {
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val last = s.latest.values.maxOfOrNull { it.takenAt }
                Text(
                    last?.let { "Son ölçüm: ${formatDate(it)}" } ?: "Henüz ölçüm yok",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    ModuleType.entries.forEach { m ->
                        val measured = s.latest.containsKey(m)
                        Surface(
                            shape = RoundedCornerShape(7.dp),
                            color = if (measured) m.accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Icon(
                                m.icon,
                                contentDescription = "${m.title}: " + if (measured) "ölçüldü" else "ölçülmedi",
                                modifier = Modifier.padding(5.dp).size(15.dp),
                                tint = if (measured) m.accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }
                }
            }
        }
    }
}
