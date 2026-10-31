package com.cardmaster

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Form(val card: BizCard, val isNew: Boolean, val fromScan: Boolean) : Screen
    data object Settings : Screen
}

class VM(app: Application) : AndroidViewModel(app) {
    private val dao = AppDb.get(app).dao()
    private val prefs = app.getSharedPreferences("cm", 0)
    val cards = dao.all().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    var query by mutableStateOf("")
    var screen: Screen by mutableStateOf(Screen.Home)
    var busy by mutableStateOf(false)
    var errorText by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var apiKey by mutableStateOf(prefs.getString("key", "") ?: "")
    var model by mutableStateOf(prefs.getString("model_v2", "gemini-3.5-flash") ?: "gemini-3.5-flash")

    fun saveSettings(k: String, m: String) {
        apiKey = k.trim()
        model = m.trim().ifEmpty { "gemini-3.5-flash" }
        prefs.edit().putString("key", apiKey).putString("model_v2", model).apply()
        screen = Screen.Home
        message = "Ayarlar kaydedildi"
    }

    fun scan(path: String) {
        if (apiKey.isBlank()) {
            message = "Önce Ayarlar'dan Gemini API anahtarını girin"
            screen = Screen.Settings
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val (ex, logo) = withContext(Dispatchers.IO) {
                    val bmp = loadBitmap(path)
                    val e = Gemini.scan(bmp, apiKey, model)
                    e to cropLogo(bmp, e.logoBox, cardsDir(getApplication()))
                }
                screen = Screen.Form(
                    BizCard(
                        name = ex.name, company = ex.company, jobTitle = ex.jobTitle,
                        mobile = ex.mobile, phone = ex.phone, email = ex.email,
                        website = ex.website, address = ex.address, city = ex.city,
                        logoPath = logo, photoPath = path
                    ), isNew = true, fromScan = true
                )
            } catch (e: Exception) {
                errorText = "OCR hatası: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun scanUri(uri: Uri) {
        viewModelScope.launch {
            val path = withContext(Dispatchers.IO) {
                val f = File(cardsDir(getApplication()), "photo_${System.currentTimeMillis()}.jpg")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    f.outputStream().use { o -> i.copyTo(o) }
                }
                f.absolutePath
            }
            scan(path)
        }
    }

    fun save(c: BizCard, toContacts: Boolean) {
        viewModelScope.launch {
            dao.upsert(c)
            message = if (toContacts) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { ContactsWriter.insert(getApplication(), c) }.isSuccess
                }
                if (ok) "Kaydedildi ve rehbere eklendi" else "Kaydedildi (rehbere yazılamadı)"
            } else "Kaydedildi"
            screen = Screen.Home
        }
    }

    fun delete(c: BizCard) {
        viewModelScope.launch {
            dao.delete(c)
            message = "Silindi"
            screen = Screen.Home
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

@Composable
fun App(vm: VM = viewModel()) {
    val ctx = LocalContext.current
    LaunchedEffect(vm.message) {
        vm.message?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); vm.message = null }
    }
    vm.errorText?.let { t ->
        AlertDialog(
            onDismissRequest = { vm.errorText = null },
            title = { Text("Hata") },
            text = { Text(t, fontSize = 13.sp, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { vm.errorText = null }) { Text("Tamam") } }
        )
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        when (val s = vm.screen) {
            Screen.Home -> HomeScreen(vm)
            is Screen.Form -> FormScreen(vm, s)
            Screen.Settings -> SettingsScreen(vm)
        }
        if (vm.busy) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)) {
                Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Kartvizit okunuyor…", color = MaterialTheme.colorScheme.surface)
                }
            }
        }
    }
}

@Composable
fun Img(path: String, modifier: Modifier, scale: ContentScale = ContentScale.Fit) {
    if (path.isBlank()) return
    val bmp by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadBitmap(path, 1000).asImageBitmap() }.getOrNull()
        }
    }
    bmp?.let { Image(it, null, modifier, contentScale = scale) }
}

@Composable
fun HomeScreen(vm: VM) {
    val ctx = LocalContext.current
    val all by vm.cards.collectAsState()
    val list = remember(all, vm.query) { all.filter { matches(it, vm.query) } }
    var pending by rememberSaveable { mutableStateOf("") }

    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok && pending.isNotEmpty()) vm.scan(pending)
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { vm.scanUri(it) }
    }
    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { vm.query = it }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("CardMaster", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { vm.screen = Screen.Settings }) { Text("⚙ Ayarlar") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = vm.query, onValueChange = { vm.query = it },
                label = { Text("Ara: isim, firma, şehir…") },
                singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = {
                try {
                    speech.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Kişi, firma veya şehir söyleyin"))
                } catch (e: Exception) {
                    Toast.makeText(ctx, "Sesli arama bu telefonda kullanılamıyor", Toast.LENGTH_LONG).show()
                }
            }) { Text("🎤") }
        }
        Text("${list.size} kayıt", fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))

        LazyColumn(Modifier.weight(1f)) {
            items(list, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    .clickable { vm.screen = Screen.Form(c, false, false) }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (c.logoPath.isNotBlank()) {
                            Img(c.logoPath, Modifier.size(52.dp))
                        } else {
                            Surface(Modifier.size(52.dp), shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.primaryContainer) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text((c.name.ifBlank { c.company }).take(1).uppercase(),
                                        fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(c.name.ifBlank { "(isimsiz)" }, fontWeight = FontWeight.Bold)
                            if (c.company.isNotBlank()) Text(c.company, fontSize = 14.sp)
                            Text(listOf(c.mobile, c.city).filter { it.isNotBlank() }.joinToString(" • "),
                                fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val f = File(cardsDir(ctx), "photo_${System.currentTimeMillis()}.jpg")
                pending = f.absolutePath
                cam.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f))
            }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("📷 Tara") }
            OutlinedButton(onClick = { pick.launch("image/*") },
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("🖼 Galeri") }
            OutlinedButton(onClick = { vm.screen = Screen.Form(BizCard(), true, false) },
                modifier = Modifier.weight(1f), contentPadding = PaddingValues(4.dp)) { Text("✍ Manuel") }
        }
    }
}

@Composable
fun Field(label: String, value: String, onChange: (String) -> Unit,
          kb: KeyboardType = KeyboardType.Text, lines: Int = 1) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        singleLine = lines == 1, minLines = lines
    )
}

@Composable
fun FormScreen(vm: VM, s: Screen.Form) {
    val ctx = LocalContext.current
    BackHandler { vm.screen = Screen.Home }
    var c by remember(s) { mutableStateOf(s.card) }
    var toContacts by remember(s) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        toContacts = granted
        if (!granted) Toast.makeText(ctx, "Rehber izni verilmedi", Toast.LENGTH_SHORT).show()
    }
    fun setContacts(v: Boolean) {
        if (v && ContextCompat.checkSelfPermission(ctx, Manifest.permission.WRITE_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) perm.launch(Manifest.permission.WRITE_CONTACTS)
        else toContacts = v
    }
    LaunchedEffect(s) { if (s.fromScan) setContacts(true) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text(
            when {
                s.fromScan -> "OCR sonucu — kontrol edip düzeltin, sonra onaylayın"
                s.isNew -> "Yeni kartvizit"
                else -> "Kartviziti düzenle"
            }, fontSize = 18.sp, fontWeight = FontWeight.Bold
        )
        if (c.photoPath.isNotBlank()) {
            Img(c.photoPath, Modifier.fillMaxWidth().heightIn(max = 220.dp).padding(vertical = 8.dp))
        }
        if (c.logoPath.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Logo: ")
                Img(c.logoPath, Modifier.size(56.dp))
                TextButton(onClick = { c = c.copy(logoPath = "") }) { Text("Logoyu kaldır") }
            }
        }
        Field("Ad Soyad", c.name, { c = c.copy(name = it) })
        Field("Firma ünvanı", c.company, { c = c.copy(company = it) })
        Field("Görev / Pozisyon", c.jobTitle, { c = c.copy(jobTitle = it) })
        Field("GSM", c.mobile, { c = c.copy(mobile = it) }, KeyboardType.Phone)
        Field("Sabit telefon", c.phone, { c = c.copy(phone = it) }, KeyboardType.Phone)
        Field("E-posta", c.email, { c = c.copy(email = it) }, KeyboardType.Email)
        Field("Web sitesi", c.website, { c = c.copy(website = it) }, KeyboardType.Uri)
        Field("Adres", c.address, { c = c.copy(address = it) }, lines = 2)
        Field("Şehir", c.city, { c = c.copy(city = it) })
        Field("Not (ör. Bursa'da yazılımda tanıştık, Mehdi bey vasıtasıyla)", c.note,
            { c = c.copy(note = it) }, lines = 3)

        if (s.isNew) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = toContacts, onCheckedChange = { setContacts(it) })
                Text("Telefon rehberine de kaydet")
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c.mobile.isNotBlank() || c.phone.isNotBlank()) OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.mobile.ifBlank { c.phone })))
                }) { Text("📞 Ara") }
                if (c.email.isNotBlank()) OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + c.email)))
                }) { Text("✉ Mail") }
                OutlinedButton(onClick = { setContacts(true) }) { Text("👤 Rehbere ekle") }
            }
            if (toContacts) Text("Kaydedince rehbere de eklenecek", fontSize = 12.sp)
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.save(c, toContacts) }, modifier = Modifier.weight(1f)) {
                Text(if (s.fromScan) "✔ Onayla ve kaydet" else "Kaydet")
            }
            OutlinedButton(onClick = { vm.screen = Screen.Home }, modifier = Modifier.weight(1f)) { Text("İptal") }
        }
        if (!s.isNew) {
            TextButton(onClick = { confirmDelete = true }) {
                Text("🗑 Sil", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Silinsin mi?") },
        text = { Text("${c.name.ifBlank { c.company }} kaydı silinecek.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(s.card) }) { Text("Sil") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Vazgeç") } }
    )
}

@Composable
fun SettingsScreen(vm: VM) {
    BackHandler { vm.screen = Screen.Home }
    var key by remember { mutableStateOf(vm.apiKey) }
    var model by remember { mutableStateOf(vm.model) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Text("Ayarlar", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Gemini API anahtarı yalnızca bu telefonda saklanır, kodun içinde yer almaz.", fontSize = 13.sp)
        OutlinedTextField(
            value = key, onValueChange = { key = it }, label = { Text("Gemini API Key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        OutlinedTextField(
            value = model, onValueChange = { model = it }, label = { Text("Model") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveSettings(key, model) }) { Text("Kaydet") }
            OutlinedButton(onClick = { vm.screen = Screen.Home }) { Text("Geri") }
        }
    }
}
