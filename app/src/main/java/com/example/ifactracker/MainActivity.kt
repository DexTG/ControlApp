package com.example.ifactracker


import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.Keep
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

private val Application.dataStore by preferencesDataStore(name = "ifac_progress")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { IFACApp() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IFACApp() {
    MaterialTheme {
        val vm: IFACViewModel = viewModel(factory = IFACViewModel.factory(LocalContext.current.applicationContext as Application))
        val uiState by vm.uiState.collectAsState()

        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TopAppBar(
                    title = { Text("IFAC Tracker") },
                )
                SearchBar(
                    query = uiState.query,
                    onQueryChange = vm::setQuery,
                    clear = { vm.setQuery("") }
                )
                ProgressHeader(overall = uiState.overallProgress)
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.filtered) { tc ->
                        TCItemCard(
                            tc = tc,
                            checkedMap = uiState.checked,
                            onToggle = { item -> vm.toggle(tc, item) }
                        )
                    }
                    item { Spacer(Modifier.height(72.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, clear: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search TCs, bullets, keywords…") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        singleLine = true,
        keyboardActions = KeyboardActions(onSearch = { /* hide keyboard handled by system */ }),
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = clear) { Text("Clear") }
            }
        }
    )
}

@Composable
private fun ProgressHeader(overall: Float) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Overall Progress", fontWeight = FontWeight.SemiBold)
        LinearProgressIndicator(progress = { overall }, modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp))
        Text("${(overall * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun TCItemCard(
    tc: TC,
    checkedMap: Map<String, Boolean>,
    onToggle: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    ElevatedCard {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${tc.code} – ${tc.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val checkedCount = tc.items.count { checkedMap[hashKey(tc.code, it)] == true }
                    val pct = if (tc.items.isEmpty()) 0f else checkedCount.toFloat() / tc.items.size
                    LinearProgressIndicator(progress = { pct }, modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp))
                    Text("$checkedCount / ${tc.items.size} complete", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                    if (tc.keywords.isNotBlank()) {
                        Text("Keywords: ${tc.keywords}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    tc.items.forEach { line ->
                        val key = hashKey(tc.code, line)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = checkedMap[key] == true,
                                onCheckedChange = { onToggle(line) }
                            )
                            Text(line, modifier = Modifier.padding(start = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

class IFACViewModel(app: Application) : AndroidViewModel(app) {
    data class UiState(
        val all: List<TC> = emptyList(),
        val filtered: List<TC> = emptyList(),
        val checked: Map<String, Boolean> = emptyMap(),
        val query: String = "",
        val overallProgress: Float = 0f
    )

    private val dataStore = app.dataStore
    private val _query = MutableStateFlow("")
    private val _all = MutableStateFlow<List<TC>>(emptyList())

    val uiState: StateFlow<UiState> = combine(
        _all,
        _query,
        dataStore.data.map { it.asMap().mapKeys { e -> e.key.name } // Preferences -> Map<String, Any>
            .mapValues { (k, v) -> v as? Boolean ?: false }
        }
    ) { all, query, checked ->
        val filt = if (query.isBlank()) all else {
            val q = query.trim().lowercase()
            all.filter { tc ->
                tc.code.lowercase().contains(q) ||
                tc.name.lowercase().contains(q) ||
                tc.keywords.lowercase().contains(q) ||
                tc.items.any { it.lowercase().contains(q) }
            }
        }
        val totalItems = all.sumOf { it.items.size }
        val done = all.sumOf { tc -> tc.items.count { checked[hashKey(tc.code, it)] == true } }
        UiState(
            all = all,
            filtered = filt,
            checked = checked,
            query = query,
            overallProgress = if (totalItems == 0) 0f else done.toFloat() / totalItems
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    init {
        viewModelScope.launch {
            _all.value = loadFromAssets(app = app)
        }
    }

    fun setQuery(q: String) { _query.value = q }

    fun toggle(tc: TC, item: String) {
        val key = booleanPreferencesKey(hashKey(tc.code, item))
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val current = prefs[key] ?: false
                prefs[key] = !current
            }
        }
    }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = viewModelFactory {
        initializer { IFACViewModel(app) }
    }
}
}

@Serializable
@Keep
data class TC(
    val code: String,
    val name: String,
    val items: List<String>,
    val keywords: String = ""
)

private suspend fun loadFromAssets(app: Application): List<TC> {
    val jsonText = app.assets.open("ifac_tcs.json").bufferedReader().use { it.readText() }
    val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    val wrapper = json.decodeFromString(TCWrapper.serializer(), jsonText)
    return wrapper.tcs
}

@Serializable
private data class TCWrapper(val tcs: List<TC>)

private fun hashKey(code: String, text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest("$code::$text".toByteArray())
    return buildString {
        bytes.take(12).forEach { b -> append(((b.toInt() and 0xFF) + 0x100).toString(16).substring(1)) }
    }
}
