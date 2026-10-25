package com.eirahoutmoss.nexyapi.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.eirahoutmoss.nexyapi.AppContainer
import com.eirahoutmoss.nexyapi.data.StructureEntity
import com.eirahoutmoss.nexyapi.data.StructureInput
import com.eirahoutmoss.nexyapi.ui.components.errorText
import kotlinx.coroutines.launch
import java.time.Year

class StructureEditViewModel(private val c: AppContainer, private val structureId: String?) : ViewModel() {
    var loading by mutableStateOf(structureId != null); private set
    var saving by mutableStateOf(false); private set
    var notFound by mutableStateOf(false); private set

    var name by mutableStateOf("")
    var address by mutableStateOf("")
    var floor by mutableStateOf("")
    var buildYear by mutableStateOf("")
    var notes by mutableStateOf("")

    var nameError by mutableStateOf<String?>(null); private set
    var yearError by mutableStateOf<String?>(null); private set
    var generalError by mutableStateOf<String?>(null); private set

    private var existing: StructureEntity? = null

    init {
        if (structureId != null) viewModelScope.launch {
            val s = c.structures.get(structureId)
            if (s == null) {
                notFound = true
            } else {
                existing = s
                name = s.name; address = s.address; floor = s.floor
                buildYear = s.buildYear?.toString().orEmpty(); notes = s.notes
            }
            loading = false
        }
    }

    /** Başarılıysa yapının kimliğini döndürür. */
    suspend fun save(): String? {
        if (saving) return null
        nameError = null; yearError = null; generalError = null
        val input = StructureInput(name, address, floor, buildYear, notes)
        val year = when (val v = input.validate(Year.now().value)) {
            is StructureInput.Validation.Error -> {
                when (v.field) {
                    StructureInput.Field.NAME -> nameError = v.message
                    StructureInput.Field.BUILD_YEAR -> yearError = v.message
                }
                return null
            }
            is StructureInput.Validation.Ok -> v.buildYear
        }
        saving = true
        return try {
            val now = System.currentTimeMillis()
            val e = existing
            if (e == null) {
                // Sınır, kaydetme anında bir kez daha denetlenir (iki sekmeden aynı anda ekleme vb.).
                if (!c.licenses.state.value.plan.canAddStructure(c.structures.count())) {
                    generalError = "Ücretsiz sürümde tek yapı kaydedilebilir."
                    null
                } else {
                    c.structures.create(input, year, now)
                }
            } else {
                c.structures.update(e, input, year, now)
                e.id
            }
        } catch (t: Exception) {
            generalError = "Kaydedilemedi: ${t.message ?: t.javaClass.simpleName}"
            null
        } finally {
            saving = false
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StructureEditScreen(structureId: String?, onDone: (String?) -> Unit, onBack: () -> Unit) {
    val container = com.eirahoutmoss.nexyapi.ui.appContainer()
    val vm: StructureEditViewModel = viewModel(
        key = "edit-${structureId ?: "new"}",
        factory = viewModelFactory { initializer { StructureEditViewModel(container, structureId) } },
    )
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (structureId == null) "Yeni Yapı" else "Yapıyı Düzenle") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Geri") }
                },
            )
        },
    ) { padding ->
        if (vm.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        if (vm.notFound) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("Yapı bulunamadı.") }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text("Yapı adı *") }, placeholder = { Text("Örn. Evim") },
                isError = vm.nameError != null, supportingText = errorText(vm.nameError),
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.address, onValueChange = { vm.address = it },
                label = { Text("Adres") }, minLines = 2,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = vm.floor, onValueChange = { vm.floor = it },
                    label = { Text("Kat") }, singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.buildYear, onValueChange = { v -> vm.buildYear = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Yapım yılı") }, singleLine = true,
                    isError = vm.yearError != null, supportingText = errorText(vm.yearError),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = vm.notes, onValueChange = { vm.notes = it },
                label = { Text("Notlar") }, minLines = 3,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.generalError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { scope.launch { vm.save()?.let(onDone) } },
                enabled = !vm.saving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (vm.saving) "Kaydediliyor…" else "Kaydet") }
        }
    }
}
