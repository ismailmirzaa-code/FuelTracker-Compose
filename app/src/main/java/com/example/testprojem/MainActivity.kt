package com.example.testprojem

import android.Manifest
import android.app.DatePickerDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

val Context.dataStore by preferencesDataStore(name = "arac_ayarlari")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        bildirimKanaliniOlustur(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val viewModel: AracViewModel = viewModel(factory = AracViewModelFactory(applicationContext))
                    AnaEkran(viewModel)
                }
            }
        }
    }
}

// ========================================================
// 1. DATA KATMANI (ROOM DB)
// ========================================================

@Entity(tableName = "yakit_kayitlari")
data class Yakit(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val odo: Double,
    val litre: Double,
    val tl: Double,
    val isFull: Boolean,
    val tarih: Long = System.currentTimeMillis()
)

@Entity(tableName = "bakim_kayitlari")
data class Bakim(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val baslik: String,
    val tur: String,
    val odo: Double,
    val sonrakiHedefOdo: Double = 0.0,
    val tutar: Double,
    val tarih: Long = System.currentTimeMillis(),
    val sonrakiTarih: Long = 0L,
    val not: String = ""
)

@Dao
interface AracDao {
    @Query("SELECT * FROM yakit_kayitlari ORDER BY odo ASC")
    fun tumYakitlar(): Flow<List<Yakit>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun yakitEkle(yakit: Yakit): Long

    @Update
    suspend fun yakitGuncelle(yakit: Yakit)

    @Delete
    suspend fun yakitSil(yakit: Yakit)

    @Query("SELECT * FROM bakim_kayitlari ORDER BY tarih DESC")
    fun tumBakimlar(): Flow<List<Bakim>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun bakimEkle(bakim: Bakim): Long

    @Update
    suspend fun bakimGuncelle(bakim: Bakim)

    @Delete
    suspend fun bakimSil(bakim: Bakim)
}

@Database(entities = [Yakit::class, Bakim::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun aracDao(): AracDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "arac_takip_pro.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ========================================================
// 2. VIEWMODEL (+ OTOMATİK ESKİ VERİ KURTARMA MOTORU)
// ========================================================

class AracViewModel(private val context: Context) : ViewModel() {
    private val dao = AppDatabase.getDatabase(context).aracDao()
    private val KAPASITE_KEY = doublePreferencesKey("depo_kapasitesi")

    val yakitlar: StateFlow<List<Yakit>> = dao.tumYakitlar()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val bakimlar: StateFlow<List<Bakim>> = dao.tumBakimlar()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val depoKapasitesi: StateFlow<Double> = context.dataStore.data
        .map { it[KAPASITE_KEY] ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    init {
        eskiVerileriKurtarVeAktar()
    }

    private fun eskiVerileriKurtarVeAktar() {
        viewModelScope.launch {
            val prefs = context.getSharedPreferences("MotorLog", Context.MODE_PRIVATE)
            val eskiMetin = prefs.getString("kayitlar", "") ?: ""

            if (eskiMetin.isNotEmpty()) {
                val kurtarilanKayitlar = eskiMetin.split(";").mapNotNull { satir ->
                    val parcalar = satir.split(",")
                    if (parcalar.size >= 4) {
                        val eskiZaman = parcalar[0].toLongOrNull() ?: System.currentTimeMillis()
                        val odo = parcalar[1].toDoubleOrNull() ?: 0.0
                        val litre = parcalar[2].toDoubleOrNull() ?: 0.0
                        val tl = parcalar[3].toDoubleOrNull() ?: 0.0
                        val isFull = if (parcalar.size == 5) parcalar[4].toBoolean() else true

                        Yakit(id = 0, odo = odo, litre = litre, tl = tl, isFull = isFull, tarih = eskiZaman)
                    } else null
                }

                if (kurtarilanKayitlar.isNotEmpty()) {
                    kurtarilanKayitlar.forEach { dao.yakitEkle(it) }
                    prefs.edit().remove("kayitlar").apply()
                }
            }
        }
    }

    fun depoKapasitesiKaydet(kapasite: Double) {
        viewModelScope.launch { context.dataStore.edit { it[KAPASITE_KEY] = kapasite } }
    }

    fun yakitEkle(yakit: Yakit) = viewModelScope.launch { dao.yakitEkle(yakit); bildirimKontrolEt() }
    fun yakitGuncelle(yakit: Yakit) = viewModelScope.launch { dao.yakitGuncelle(yakit) }
    fun yakitSil(yakit: Yakit) = viewModelScope.launch { dao.yakitSil(yakit) }

    fun bakimEkle(bakim: Bakim) = viewModelScope.launch { dao.bakimEkle(bakim); bildirimKontrolEt() }
    fun bakimGuncelle(bakim: Bakim) = viewModelScope.launch { dao.bakimGuncelle(bakim) }
    fun bakimSil(bakim: Bakim) = viewModelScope.launch { dao.bakimSil(bakim) }

    fun bildirimKontrolEt() {
        val yListe = yakitlar.value
        val bListe = bakimlar.value
        val guncelKm = yListe.maxOfOrNull { it.odo } ?: 0.0

        val sonYag = bListe.filter { it.tur == "Yağ Değişimi" }.maxByOrNull { it.odo }
        if (sonYag != null && guncelKm > 0) {
            val hedefKm = if (sonYag.sonrakiHedefOdo > 0) sonYag.sonrakiHedefOdo else (sonYag.odo + 10000.0)
            val kalanKm = hedefKm - guncelKm
            if (kalanKm in 0.0..1000.0) {
                bildirimGonder(context, "🛢️ Yağ Değişimi Yaklaştı!", "Yağ değişimine sadece ${kalanKm.roundToInt()} km kaldı.")
            } else if (kalanKm < 0) {
                bildirimGonder(context, "⚠️ Yağ Değişimi Gecikti!", "Yağ bakımını ${(-kalanKm).roundToInt()} km aştınız!")
            }
        }

        val sonMuayene = bListe.filter { it.tur == "Muayene" }.maxByOrNull { it.tarih }
        if (sonMuayene != null && sonMuayene.sonrakiTarih > 0) {
            val kalanGun = ((sonMuayene.sonrakiTarih - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
            if (kalanGun in 0..30) {
                bildirimGonder(context, "📋 Araç Muayenesi Yaklaştı!", "Muayene geçerlilik sürenizin bitmesine $kalanGun gün kaldı.")
            }
        }
    }

    fun csvDisaAktar() {
        val sb = StringBuilder()
        sb.append("--- YAKIT KAYITLARI ---\nID,Tarih,KM,Litre,TL,DepoFull\n")
        yakitlar.value.forEach {
            sb.append("${it.id},${formatTarih(it.tarih)},${it.odo},${it.litre},${it.tl},${it.isFull}\n")
        }
        sb.append("\n--- BAKIM KAYITLARI ---\nID,Tarih,Baslik,Tur,KM,Tutar,Not\n")
        bakimlar.value.forEach {
            sb.append("${it.id},${formatTarih(it.tarih)},${it.baslik},${it.tur},${it.odo},${it.tutar},${it.not}\n")
        }

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, sb.toString())
            type = "text/plain"
        }
        val chooser = Intent.createChooser(sendIntent, "Yedek Dosyasını Paylaş (CSV)")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

class AracViewModelFactory(private val context: Context) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AracViewModel(context) as T
    }
}

// ========================================================
// 3. YARDIMCI SERVİSLER
// ========================================================

fun bildirimKanaliniOlustur(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val kanal = NotificationChannel("arac_bildirim_kanali", "Araç Hatırlatıcıları", NotificationManager.IMPORTANCE_DEFAULT)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(kanal)
    }
}

fun bildirimGonder(context: Context, baslik: String, mesaj: String) {
    val builder = NotificationCompat.Builder(context, "arac_bildirim_kanali")
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(baslik)
        .setContentText(mesaj)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)

    if (ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
        NotificationManagerCompat.from(context).notify(System.currentTimeMillis().toInt(), builder.build())
    }
}

// Kamera ile çekilen fotoğraf için geçici yüksek çözünürlüklü dosya URI'si oluşturucu
fun geciciKameraUriOlustur(context: Context): Uri? {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "fis_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
    }
    return try {
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        null
    }
}

// 📄 PDF ÇİZDİRİCİ
fun pdfBelgesiOlustur(yakitlar: List<Yakit>, bakimlar: List<Bakim>): PdfDocument {
    val pdfDocument = PdfDocument()
    val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create() // A4
    val page = pdfDocument.startPage(pageInfo)
    val canvas: Canvas = page.canvas
    val paint = Paint()

    paint.color = AndroidColor.rgb(33, 33, 33)
    paint.textSize = 20f
    paint.isFakeBoldText = true
    canvas.drawText("ARAÇ SERVİS VE MASRAF RAPORU", 40f, 60f, paint)

    paint.textSize = 10f
    paint.isFakeBoldText = false
    paint.color = AndroidColor.GRAY
    canvas.drawText("Rapor Tarihi: ${formatTarih(System.currentTimeMillis())}", 40f, 80f, paint)

    val guncelKm = yakitlar.maxOfOrNull { it.odo } ?: 0.0
    val toplamYakit = yakitlar.sumOf { it.tl }
    val toplamBakim = bakimlar.sumOf { it.tutar }

    paint.color = AndroidColor.rgb(240, 240, 240)
    canvas.drawRoundRect(40f, 100f, 555f, 170f, 8f, 8f, paint)

    paint.color = AndroidColor.BLACK
    paint.textSize = 12f
    paint.isFakeBoldText = true
    canvas.drawText("GENEL ARAÇ ÖZETİ", 55f, 125f, paint)

    paint.isFakeBoldText = false
    canvas.drawText("Güncel KM: ${guncelKm.roundToInt()} km", 55f, 145f, paint)
    canvas.drawText("Toplam Masraf: ${(toplamYakit + toplamBakim).round(2)} TL", 55f, 160f, paint)
    canvas.drawText("Yakıt: ${toplamYakit.round(2)} TL | Bakım: ${toplamBakim.round(2)} TL", 280f, 145f, paint)

    var yPos = 210f
    paint.isFakeBoldText = true
    paint.textSize = 13f
    canvas.drawText("Bakım, Onarım ve Muayene Kayıtları", 40f, yPos, paint)

    yPos += 20f
    paint.textSize = 10f
    paint.color = AndroidColor.DKGRAY
    canvas.drawText("TARİH", 40f, yPos, paint)
    canvas.drawText("İŞLEM", 130f, yPos, paint)
    canvas.drawText("KM", 300f, yPos, paint)
    canvas.drawText("TUTAR", 390f, yPos, paint)
    canvas.drawText("TÜR", 480f, yPos, paint)

    paint.color = AndroidColor.LTGRAY
    canvas.drawLine(40f, yPos + 5f, 555f, yPos + 5f, paint)

    paint.color = AndroidColor.BLACK
    paint.isFakeBoldText = false

    bakimlar.forEach { b ->
        yPos += 22f
        if (yPos < 800f) {
            canvas.drawText(formatSadeceTarih(b.tarih), 40f, yPos, paint)
            canvas.drawText(b.baslik.take(22), 130f, yPos, paint)
            canvas.drawText("${b.odo.roundToInt()} km", 300f, yPos, paint)
            canvas.drawText("${b.tutar.round(2)} TL", 390f, yPos, paint)
            canvas.drawText(b.tur, 480f, yPos, paint)
        }
    }

    pdfDocument.finishPage(page)
    return pdfDocument
}

// 📸 AKARYAKIT FİŞLERİNE ÖZEL AKILLI OCR AYRIŞTIRICISI
fun gelismisFisCozumleyici(context: Context, imageUri: Uri, onSonuc: (litre: Double?, tl: Double?, hamMetin: String) -> Unit) {
    try {
        val image = InputImage.fromFilePath(context, imageUri)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val tamMetin = visionText.text
                val satirlar = tamMetin.lines()

                var bulunanLitre: Double? = null
                var bulunanTl: Double? = null

                fun sayiParse(str: String): Double? {
                    val s = str.replace("*", "").replace("TL", "").replace("TRY", "").replace("₺", "").trim()
                    val formatli = if (s.contains(".") && s.contains(",")) {
                        s.replace(".", "").replace(",", ".")
                    } else {
                        s.replace(",", ".")
                    }
                    return formatli.toDoubleOrNull()
                }

                for (satir in satirlar) {
                    val s = satir.uppercase(Locale("tr"))

                    if ((s.contains("LİTRE") || s.contains("LITRE") || s.contains("MIKTAR") || s.contains("LT")) &&
                        !s.contains("B.FİYAT") && !s.contains("FIYAT") && !s.contains("FİYAT")
                    ) {
                        val sayiRegex = Regex("([0-9]{1,3}[.,][0-9]{1,3})")
                        val adaylar = sayiRegex.findAll(s).mapNotNull { sayiParse(it.value) }.toList()
                        val makul = adaylar.firstOrNull { it in 1.0..150.0 }
                        if (makul != null && bulunanLitre == null) {
                            bulunanLitre = makul
                        }
                    }

                    if (s.contains("TOPLAM") || s.contains("TUTAR") || s.contains("ODENEN") || s.contains("ÖDENEN") || s.contains("NAKIT") || s.contains("KREDI")) {
                        val regex = Regex("([0-9]{1,3}[.,][0-9]{3}[.,][0-9]{2}|[0-9]+[.,][0-9]{2})")
                        val adaylar = regex.findAll(s).mapNotNull { sayiParse(it.value) }.toList()
                        val buyuk = adaylar.maxOrNull()
                        if (buyuk != null && (bulunanTl == null || buyuk > bulunanTl)) {
                            bulunanTl = buyuk
                        }
                    }
                }

                if (bulunanTl == null) {
                    val tumSayilar = Regex("([0-9]{1,3}[.,][0-9]{3}[.,][0-9]{2}|[0-9]{2,5}[.,][0-9]{2})")
                        .findAll(tamMetin).mapNotNull { sayiParse(it.value) }.toList()
                    bulunanTl = tumSayilar.filter { it >= 100.0 }.maxOrNull()
                }

                if (bulunanLitre == null) {
                    val tumLitreAdaylari = Regex("([0-9]{1,3}[.,][0-9]{2})")
                        .findAll(tamMetin).mapNotNull { sayiParse(it.value) }
                        .filter { it in 3.0..120.0 && it != bulunanTl }.toList()

                    if (bulunanTl != null) {
                        bulunanLitre = tumLitreAdaylari.firstOrNull { aday ->
                            val oran = bulunanTl / aday
                            oran in 30.0..60.0
                        } ?: tumLitreAdaylari.firstOrNull()
                    } else {
                        bulunanLitre = tumLitreAdaylari.firstOrNull()
                    }
                }

                when {
                    bulunanLitre != null && bulunanTl != null -> {
                        Toast.makeText(context, "✅ Fiş Okundu!\n⛽ Litre: $bulunanLitre L\n💰 Tutar: $bulunanTl TL", Toast.LENGTH_LONG).show()
                    }
                    bulunanTl != null -> {
                        Toast.makeText(context, "⚠️ Tutar ($bulunanTl TL) bulundu. Litreyi kontrol edin.", Toast.LENGTH_LONG).show()
                    }
                    bulunanLitre != null -> {
                        Toast.makeText(context, "⚠️ Litre ($bulunanLitre L) bulundu. Tutarı kontrol edin.", Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        Toast.makeText(context, "❌ Fişteki tutar/litre tespit edilemedi.", Toast.LENGTH_LONG).show()
                    }
                }

                onSonuc(bulunanLitre, bulunanTl, tamMetin)
            }
            .addOnFailureListener { e ->
                Toast.makeText(context, "OCR Hatası: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
                onSonuc(null, null, "")
            }
    } catch (e: Exception) {
        Toast.makeText(context, "Görsel yüklenemedi: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        onSonuc(null, null, "")
    }
}

fun formatTarih(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
fun formatSadeceTarih(timestamp: Long): String = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
fun formatAyYil(timestamp: Long): String = SimpleDateFormat("MMMM yyyy", Locale("tr")).format(Date(timestamp)).replaceFirstChar { it.uppercase() }

fun tarihSeciciGoster(context: Context, baslangicTarihi: Long, onSecildi: (Long) -> Unit) {
    val c = Calendar.getInstance().apply { timeInMillis = baslangicTarihi }
    DatePickerDialog(context, { _, y, m, d ->
        val yeni = Calendar.getInstance().apply { set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, d) }
        onSecildi(yeni.timeInMillis)
    }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
}

fun Double.round(decimals: Int): Double {
    var multiplier = 1.0
    repeat(decimals) { multiplier *= 10 }
    return (this * multiplier).roundToInt() / multiplier
}

// ========================================================
// 4. ANA EKRAN & BOTTOM BAR
// ========================================================

@Composable
fun AnaEkran(viewModel: AracViewModel) {
    val context = LocalContext.current
    val yakitlar by viewModel.yakitlar.collectAsState()
    val bakimlar by viewModel.bakimlar.collectAsState()
    val depoKapasitesi by viewModel.depoKapasitesi.collectAsState()

    var seciliSekme by remember { mutableIntStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(icon = { Text("📊") }, label = { Text("Özet") }, selected = seciliSekme == 0, onClick = { seciliSekme = 0 })
                NavigationBarItem(icon = { Text("➕") }, label = { Text("Ekle") }, selected = seciliSekme == 1, onClick = { seciliSekme = 1 })
                NavigationBarItem(icon = { Text("⛽") }, label = { Text("Yakıt") }, selected = seciliSekme == 2, onClick = { seciliSekme = 2 })
                NavigationBarItem(icon = { Text("🔧") }, label = { Text("Bakım") }, selected = seciliSekme == 3, onClick = { seciliSekme = 3 })
                NavigationBarItem(icon = { Text("🛣️") }, label = { Text("Yolculuk") }, selected = seciliSekme == 4, onClick = { seciliSekme = 4 })
                NavigationBarItem(icon = { Text("💰") }, label = { Text("Aylık") }, selected = seciliSekme == 5, onClick = { seciliSekme = 5 })
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            when (seciliSekme) {
                0 -> OzetEkrani(context, yakitlar, bakimlar, depoKapasitesi, onKapasiteKaydet = { viewModel.depoKapasitesiKaydet(it) }, onCsvYedek = { viewModel.csvDisaAktar() })
                1 -> EkleAnaEkrani(yakitlar = yakitlar, onYakitKaydet = { viewModel.yakitEkle(it); seciliSekme = 2 }, onBakimKaydet = { viewModel.bakimEkle(it); seciliSekme = 3 })
                2 -> YakitListeEkrani(context, yakitlar, onGuncelle = { viewModel.yakitGuncelle(it) }, onSil = { viewModel.yakitSil(it) })
                3 -> BakimListeEkrani(context, bakimlar, yakitlar, onGuncelle = { viewModel.bakimGuncelle(it) }, onSil = { viewModel.bakimSil(it) })
                4 -> YolculukHesaplayiciEkrani(yakitlar, depoKapasitesi)
                5 -> AylikMasrafEkrani(yakitlar, bakimlar)
            }
        }
    }
}

// ========================================================
// 5. SEKME 1: ÖZET EKRANI
// ========================================================

@Composable
fun OzetEkrani(
    context: Context,
    yakitlar: List<Yakit>,
    bakimlar: List<Bakim>,
    depoKapasitesi: Double,
    onKapasiteKaydet: (Double) -> Unit,
    onCsvYedek: () -> Unit
) {
    var girilenKapasite by remember(depoKapasitesi) { mutableStateOf(if (depoKapasitesi > 0) depoKapasitesi.toString() else "") }
    val guncelKm = yakitlar.maxOfOrNull { it.odo } ?: 0.0
    val toplamYakitTl = yakitlar.sumOf { it.tl }
    val toplamBakimTl = bakimlar.sumOf { it.tutar }

    val pdfKaydetLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val pdfDoc = pdfBelgesiOlustur(yakitlar, bakimlar)
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    pdfDoc.writeTo(out)
                    pdfDoc.close()
                }
                Toast.makeText(context, "✅ PDF başarıyla cihazınıza kaydedildi!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Kaydetme hatası: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Araç Özeti", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Row {
                    OutlinedButton(onClick = onCsvYedek, modifier = Modifier.padding(end = 6.dp)) {
                        Text("📥 CSV", fontSize = 12.sp)
                    }
                    Button(onClick = {
                        val dosyaAdi = "Arac_Raporu_${SimpleDateFormat("ddMMyyyy", Locale.getDefault()).format(Date())}.pdf"
                        pdfKaydetLauncher.launch(dosyaAdi)
                    }) {
                        Text("📄 PDF İndir", fontSize = 12.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Toplam Araç Masrafı", fontSize = 14.sp)
                    Text("${(toplamYakitTl + toplamBakimTl).round(2)} TL", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("⛽ Yakıt: ${toplamYakitTl.round(2)} TL", fontSize = 13.sp)
                        Text("🔧 Bakım: ${toplamBakimTl.round(2)} TL", fontSize = 13.sp)
                    }
                    if (guncelKm > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("📌 Güncel KM: ${guncelKm.roundToInt()} km", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            val sonYag = bakimlar.filter { it.tur == "Yağ Değişimi" }.maxByOrNull { it.odo }
            val sonMuayene = bakimlar.filter { it.tur == "Muayene" }.maxByOrNull { it.tarih }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("🔔 Yaklaşan Bakım & Muayene", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (sonYag != null && guncelKm > 0) {
                        val hedefKm = if (sonYag.sonrakiHedefOdo > 0) sonYag.sonrakiHedefOdo else (sonYag.odo + 10000.0)
                        val kalanKm = hedefKm - guncelKm
                        val renk = if (kalanKm <= 1000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        Text("🛢️ Yağ Değişimi: ${if (kalanKm > 0) "${kalanKm.roundToInt()} km kaldı" else "${(-kalanKm).roundToInt()} km gecikti!"}", color = renk, fontWeight = FontWeight.Bold)
                    } else {
                        Text("🛢️ Yağ değişimi kaydı bulunmuyor.", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (sonMuayene != null && sonMuayene.sonrakiTarih > 0) {
                        val kalanGun = ((sonMuayene.sonrakiTarih - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)).toInt()
                        val renk = if (kalanGun <= 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                        Text("📋 Muayene: ${if (kalanGun > 0) "$kalanGun gün kaldı" else "$(-kalanGun) gün önce geçti!"}", color = renk, fontWeight = FontWeight.Bold)
                    } else {
                        Text("📋 Muayene bitiş tarihi kaydedilmemiş.", fontSize = 13.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (yakitlar.isNotEmpty()) {
            val fullKayitlar = yakitlar.filter { it.isFull }
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Ortalama Tüketim (Full'den Full'e)", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
                        if (fullKayitlar.size >= 2) {
                            val ilkFull = fullKayitlar.first()
                            val sonFull = fullKayitlar.last()
                            val mesafe = sonFull.odo - ilkFull.odo
                            val yakilanLitre = yakitlar.filter { it.odo > ilkFull.odo && it.odo <= sonFull.odo }.sumOf { it.litre }
                            val yakilanTl = yakitlar.filter { it.odo > ilkFull.odo && it.odo <= sonFull.odo }.sumOf { it.tl }

                            val ortTuketim = if (mesafe > 0) (yakilanLitre / mesafe) * 100 else 0.0
                            val ortMaliyet = if (mesafe > 0) (yakilanTl / mesafe) else 0.0

                            Text("Genel Tüketim: ${ortTuketim.round(2)} L / 100km", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            Text("KM Başı Maliyet: ${ortMaliyet.round(2)} TL / km", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        } else {
                            Text("Ortalama hesabı için en az 2 'Full Depo' kaydı gereklidir.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("⚙️ Depo Kapasitesi", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = girilenKapasite,
                            onValueChange = { girilenKapasite = it },
                            label = { Text("Litre") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { girilenKapasite.toDoubleOrNull()?.let { onKapasiteKaydet(it) } }) {
                            Text("Kaydet")
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 6. SEKME 2: EKLE (+ 📸 KAMERA VE 🖼️ GALERİ İLE FİŞ OKUMA)
// ========================================================

@Composable
fun EkleAnaEkrani(
    yakitlar: List<Yakit>,
    onYakitKaydet: (Yakit) -> Unit,
    onBakimKaydet: (Bakim) -> Unit
) {
    var seciliFormSekme by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val sonKayitliKm = yakitlar.maxOfOrNull { it.odo } ?: 0.0

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        TabRow(selectedTabIndex = seciliFormSekme) {
            Tab(selected = seciliFormSekme == 0, onClick = { seciliFormSekme = 0 }, text = { Text("⛽ Yakıt Fişi") })
            Tab(selected = seciliFormSekme == 1, onClick = { seciliFormSekme = 1 }, text = { Text("🔧 Bakım & Masraf") })
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (seciliFormSekme == 0) {
            YakitEkleFormu(context, sonKayitliKm, onYakitKaydet)
        } else {
            BakimEkleFormu(context, sonKayitliKm, onBakimKaydet)
        }
    }
}

@Composable
fun YakitEkleFormu(context: Context, sonKm: Double, onKaydet: (Yakit) -> Unit) {
    var odo by remember { mutableStateOf("") }
    var litre by remember { mutableStateOf("") }
    var tl by remember { mutableStateOf("") }
    var isFull by remember { mutableStateOf(true) }
    var secilenTarih by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var taraniyor by remember { mutableStateOf(false) }
    var okunanHamMetin by remember { mutableStateOf<String?>(null) }
    var cekilenFotoUri by remember { mutableStateOf<Uri?>(null) }

    // 1. Doğrudan Kamera ile Tam Çözünürlüklü Fotoğraf Çekici
    val kameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { basarili ->
        if (basarili && cekilenFotoUri != null) {
            taraniyor = true
            gelismisFisCozumleyici(context, cekilenFotoUri!!) { bulunanLitre, bulunanTl, hamMetin ->
                taraniyor = false
                if (bulunanLitre != null) litre = bulunanLitre.toString()
                if (bulunanTl != null) tl = bulunanTl.toString()
                if (bulunanLitre == null && bulunanTl == null) {
                    okunanHamMetin = hamMetin
                }
            }
        }
    }

    // 2. Galeriden Seçici
    val galeriLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            taraniyor = true
            gelismisFisCozumleyici(context, uri) { bulunanLitre, bulunanTl, hamMetin ->
                taraniyor = false
                if (bulunanLitre != null) litre = bulunanLitre.toString()
                if (bulunanTl != null) tl = bulunanTl.toString()
                if (bulunanLitre == null && bulunanTl == null) {
                    okunanHamMetin = hamMetin
                }
            }
        }
    }

    val girilenOdo = odo.toDoubleOrNull()
    val kmUyarisi = girilenOdo != null && sonKm > 0 && girilenOdo < sonKm

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            // YAN YANA İKİ BUTON: KAMERA İLE ÇEK & GALERİDEN SEÇ
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val yeniUri = geciciKameraUriOlustur(context)
                        if (yeniUri != null) {
                            cekilenFotoUri = yeniUri
                            kameraLauncher.launch(yeniUri)
                        } else {
                            Toast.makeText(context, "Kamera başlatılamadı.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("📸 Fotoğraf Çek", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = { galeriLauncher.launch("image/*") },
                    modifier = Modifier.weight(1f).height(48.dp)
                ) {
                    Text("🖼️ Galeriden Seç", fontSize = 13.sp)
                }
            }

            if (taraniyor) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("⏳ Fiş taranıyor ve rakamlar çözümleniyor...", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable {
                tarihSeciciGoster(context, secilenTarih) { secilenTarih = it }
            }) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("📅 Tarih: ${formatSadeceTarih(secilenTarih)}", fontWeight = FontWeight.Bold)
                    Text("Değiştir", color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = odo,
                onValueChange = { odo = it },
                label = { Text("Kadrandaki Kilometre") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                isError = kmUyarisi
            )
            if (kmUyarisi) {
                Text("⚠️ Girilen km (${girilenOdo?.roundToInt()}), son kayıtlı km'den (${sonKm.roundToInt()}) daha düşük!", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = litre,
                onValueChange = { litre = it },
                label = { Text("Alınan Litre") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = tl,
                onValueChange = { tl = it },
                label = { Text("Ödenen TL") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Depoyu Fulledim", fontWeight = FontWeight.Bold)
                        Text("Hatasız tüketim hesabı için pompa attığında işaretleyin.", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                    }
                    Switch(checked = isFull, onCheckedChange = { isFull = it })
                }
            }
            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val o = odo.toDoubleOrNull()
                    val l = litre.toDoubleOrNull()
                    val t = tl.toDoubleOrNull()
                    if (o != null && l != null && t != null) {
                        onKaydet(Yakit(odo = o, litre = l, tl = t, isFull = isFull, tarih = secilenTarih))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Yakıt Fişini Kaydet")
            }
        }
    }

    okunanHamMetin?.let { metin ->
        AlertDialog(
            onDismissRequest = { okunanHamMetin = null },
            title = { Text("Fişten Okunan Ham Metin") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 250.dp)) {
                    Text("Kamera fişten şu metni okudu ancak tutar/litre tespit edilemedi:\n", fontSize = 12.sp)
                    Text(if (metin.isBlank()) "(Hiçbir yazı algılanamadı, görsel karanlık veya çok uzak olabilir)" else metin, fontWeight = FontWeight.Medium, fontSize = 12.sp)
                }
            },
            confirmButton = {
                Button(onClick = { okunanHamMetin = null }) { Text("Anladım") }
            }
        )
    }
}

@Composable
fun BakimEkleFormu(context: Context, sonKm: Double, onKaydet: (Bakim) -> Unit) {
    val turler = listOf("Yağ Değişimi", "Muayene", "Periyodik Bakım", "Onarım / Parça", "Diğer")
    var seciliTur by remember { mutableStateOf(turler[0]) }
    var baslik by remember { mutableStateOf("") }
    var odo by remember { mutableStateOf(if (sonKm > 0) sonKm.roundToInt().toString() else "") }
    var tutar by remember { mutableStateOf("") }
    var sonrakiOdo by remember { mutableStateOf("") }
    var not by remember { mutableStateOf("") }
    var secilenTarih by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sonrakiMuayeneTarih by remember { mutableLongStateOf(System.currentTimeMillis() + (365L * 2 * 24 * 60 * 60 * 1000)) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Text("Bakım Türü", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
                turler.forEach { tur ->
                    FilterChip(selected = seciliTur == tur, onClick = { seciliTur = tur }, label = { Text(tur) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(value = if (baslik.isEmpty()) seciliTur else baslik, onValueChange = { baslik = it }, label = { Text("Başlık") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedCard(modifier = Modifier.fillMaxWidth().clickable {
                tarihSeciciGoster(context, secilenTarih) { secilenTarih = it }
            }) {
                Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("📅 Tarih: ${formatSadeceTarih(secilenTarih)}", fontWeight = FontWeight.Bold)
                    Text("Değiştir", color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(value = odo, onValueChange = { odo = it }, label = { Text("Yapıldığı KM") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(value = tutar, onValueChange = { tutar = it }, label = { Text("Tutar (TL)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(10.dp))

            if (seciliTur == "Yağ Değişimi" || seciliTur == "Periyodik Bakım") {
                OutlinedTextField(value = sonrakiOdo, onValueChange = { sonrakiOdo = it }, label = { Text("Sonraki Bakım KM (Örn: +10.000)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (seciliTur == "Muayene") {
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable {
                    tarihSeciciGoster(context, sonrakiMuayeneTarih) { sonrakiMuayeneTarih = it }
                }) {
                    Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("📋 Sonraki Muayene: ${formatSadeceTarih(sonrakiMuayeneTarih)}", fontWeight = FontWeight.Bold)
                        Text("Değiştir", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            OutlinedTextField(value = not, onValueChange = { not = it }, label = { Text("Not / Parça Bilgisi") }, modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = {
                    val o = odo.toDoubleOrNull() ?: 0.0
                    val t = tutar.toDoubleOrNull() ?: 0.0
                    val sOdo = sonrakiOdo.toDoubleOrNull() ?: if (seciliTur == "Yağ Değişimi") (o + 10000.0) else 0.0
                    val sTarih = if (seciliTur == "Muayene") sonrakiMuayeneTarih else 0L

                    onKaydet(Bakim(baslik = if (baslik.isEmpty()) seciliTur else baslik, tur = seciliTur, odo = o, sonrakiHedefOdo = sOdo, tutar = t, tarih = secilenTarih, sonrakiTarih = sTarih, not = not))
                },
                modifier = Modifier.fillMaxWidth().height(50.dp)
            ) {
                Text("Bakım Kaydet")
            }
        }
    }
}

// ========================================================
// 7. SEKME 4: 🛣️ YOLCULUK HESAPLAYICISI
// ========================================================

@Composable
fun YolculukHesaplayiciEkrani(yakitlar: List<Yakit>, depoKapasitesi: Double) {
    var mesafeGiris by remember { mutableStateOf("") }
    var gidisDonus by remember { mutableStateOf(false) }

    val fullKayitlar = yakitlar.filter { it.isFull }
    val ortalamaTuketim = remember(yakitlar) {
        if (fullKayitlar.size >= 2) {
            val ilk = fullKayitlar.first()
            val son = fullKayitlar.last()
            val mesafe = son.odo - ilk.odo
            val litre = yakitlar.filter { it.odo > ilk.odo && it.odo <= son.odo }.sumOf { it.litre }
            if (mesafe > 0) (litre / mesafe) * 100 else 6.0
        } else {
            6.0
        }
    }

    val sonLitreFiyati = remember(yakitlar) {
        yakitlar.lastOrNull()?.let { it.tl / it.litre } ?: 43.0
    }

    val mesafe = (mesafeGiris.toDoubleOrNull() ?: 0.0) * (if (gidisDonus) 2 else 1)
    val gerekenLitre = if (mesafe > 0) (mesafe * ortalamaTuketim) / 100 else 0.0
    val tahminiMaliyet = gerekenLitre * sonLitreFiyati

    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        item {
            Text("Yolculuk Maliyet Hesaplayıcı", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("Gideceğiniz mesafeye göre yakıt ve bütçe hesabı", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.height(16.dp))
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = mesafeGiris,
                        onValueChange = { mesafeGiris = it },
                        label = { Text("Gidilecek Mesafe (KM)") },
                        placeholder = { Text("Örn: 480") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Gidiş - Dönüş Hesapla (2x)")
                        Switch(checked = gidisDonus, onCheckedChange = { gidisDonus = it })
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (mesafe > 0) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("🎯 Tahmini Yolculuk Maliyeti", fontSize = 14.sp)
                        Text("${tahminiMaliyet.round(2)} TL", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("⛽ Gereken Yakıt: ${gerekenLitre.round(2)} Litre", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("🛣️ Toplam Yol: ${mesafe.roundToInt()} km", fontSize = 14.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(8.dp))

                        Text("Baz Alınan Değerler:", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)
                        Text("• Ortalama Tüketim: ${ortalamaTuketim.round(2)} L / 100km", fontSize = 12.sp)
                        Text("• Son Litre Fiyatı: ${sonLitreFiyati.round(2)} TL / Litre", fontSize = 12.sp)

                        if (depoKapasitesi > 0) {
                            val depoMenzili = (depoKapasitesi / ortalamaTuketim) * 100
                            Spacer(modifier = Modifier.height(8.dp))
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                                Text(
                                    text = if (mesafe <= depoMenzili) "✅ 1 tam depoyla (${depoMenzili.roundToInt()} km menzil) tek seferde varabilirsiniz!"
                                    else "⚠️ Yolda en az ${(mesafe / depoMenzili).toInt()} kez yakıt ikmali yapmanız gerekebilir.",
                                    modifier = Modifier.padding(8.dp),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ========================================================
// 8. LİSTE VE AYLIK EKRANLARI
// ========================================================

@Composable
fun YakitListeEkrani(context: Context, yakitlar: List<Yakit>, onGuncelle: (Yakit) -> Unit, onSil: (Yakit) -> Unit) {
    var duzenlenecekKayit by remember { mutableStateOf<Yakit?>(null) }
    var silinecekKayit by remember { mutableStateOf<Yakit?>(null) }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Geçmiş Yakıt Fişleri", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (yakitlar.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz kaydedilmiş yakıt verisi yok.", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn {
                items(yakitlar.reversed()) { kayit ->
                    val index = yakitlar.indexOfFirst { it.id == kayit.id }
                    val oncekiKayit = if (index > 0) yakitlar[index - 1] else null
                    val tripKm = if (oncekiKayit != null) kayit.odo - oncekiKayit.odo else null

                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("${kayit.odo} km", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                                Row {
                                    Text("✏️", fontSize = 20.sp, modifier = Modifier.clickable { duzenlenecekKayit = kayit }.padding(6.dp))
                                    Text("🗑️", fontSize = 20.sp, modifier = Modifier.clickable { silinecekKayit = kayit }.padding(6.dp))
                                }
                            }
                            Text("${kayit.litre} L | ${kayit.tl} TL (${(kayit.tl / kayit.litre).round(2)} TL/L)")
                            Text("📅 ${formatTarih(kayit.tarih)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

                            if (tripKm != null && tripKm > 0) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("🚗 Son alımdan beri: +${tripKm.roundToInt()} km", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }

    duzenlenecekKayit?.let { kayit ->
        YakitDuzenleDialog(context, kayit, onKapat = { duzenlenecekKayit = null }, onKaydet = { onGuncelle(it); duzenlenecekKayit = null })
    }

    silinecekKayit?.let { kayit ->
        AlertDialog(
            onDismissRequest = { silinecekKayit = null },
            icon = { Text("⚠️", fontSize = 26.sp) },
            title = { Text("Yakıt Kaydını Sil") },
            text = { Text("${kayit.odo} km'deki ${kayit.tl} TL tutarındaki kaydı silmek istediğinize emin misiniz?") },
            confirmButton = { Button(onClick = { onSil(kayit); silinecekKayit = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Sil") } },
            dismissButton = { OutlinedButton(onClick = { silinecekKayit = null }) { Text("Vazgeç") } }
        )
    }
}

@Composable
fun YakitDuzenleDialog(context: Context, kayit: Yakit, onKapat: () -> Unit, onKaydet: (Yakit) -> Unit) {
    var odo by remember { mutableStateOf(kayit.odo.toString()) }
    var litre by remember { mutableStateOf(kayit.litre.toString()) }
    var tl by remember { mutableStateOf(kayit.tl.toString()) }
    var isFull by remember { mutableStateOf(kayit.isFull) }
    var secilenTarih by remember { mutableLongStateOf(kayit.tarih) }

    AlertDialog(
        onDismissRequest = onKapat,
        title = { Text("Yakıt Fişini Düzenle") },
        text = {
            Column {
                OutlinedCard(modifier = Modifier.fillMaxWidth().clickable {
                    tarihSeciciGoster(context, secilenTarih) { secilenTarih = it }
                }) {
                    Text("📅 ${formatSadeceTarih(secilenTarih)} (Değiştir)", modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = odo, onValueChange = { odo = it }, label = { Text("KM") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = litre, onValueChange = { litre = it }, label = { Text("Litre") })
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = tl, onValueChange = { tl = it }, label = { Text("TL") })
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Depoyu Fulledim")
                    Spacer(modifier = Modifier.weight(1f))
                    Switch(checked = isFull, onCheckedChange = { isFull = it })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val o = odo.toDoubleOrNull()
                val l = litre.toDoubleOrNull()
                val t = tl.toDoubleOrNull()
                if (o != null && l != null && t != null) {
                    onKaydet(kayit.copy(odo = o, litre = l, tl = t, isFull = isFull, tarih = secilenTarih))
                }
            }) { Text("Güncelle") }
        },
        dismissButton = { OutlinedButton(onClick = onKapat) { Text("Vazgeç") } }
    )
}

@Composable
fun BakimListeEkrani(context: Context, bakimlar: List<Bakim>, yakitlar: List<Yakit>, onGuncelle: (Bakim) -> Unit, onSil: (Bakim) -> Unit) {
    var silinecekBakim by remember { mutableStateOf<Bakim?>(null) }
    val guncelKm = yakitlar.maxOfOrNull { it.odo } ?: 0.0

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Bakım & Masraflar", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        if (bakimlar.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz kaydedilmiş bakım veya masraf yok.", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn {
                items(bakimlar) { bakim ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(bakim.baslik, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text("🗑️", fontSize = 20.sp, modifier = Modifier.clickable { silinecekBakim = bakim }.padding(6.dp))
                            }
                            Text("${bakim.tutar.round(2)} TL | Yapıldığı: ${bakim.odo.roundToInt()} km")
                            Text("📅 ${formatTarih(bakim.tarih)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.secondary)

                            if (bakim.tur == "Yağ Değişimi" && guncelKm > bakim.odo) {
                                val gecenKm = guncelKm - bakim.odo
                                Text("🛢️ Bakımdan beri: ${gecenKm.roundToInt()} km geçti", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    silinecekBakim?.let { bakim ->
        AlertDialog(
            onDismissRequest = { silinecekBakim = null },
            icon = { Text("⚠️", fontSize = 26.sp) },
            title = { Text("Bakım Kaydını Sil") },
            text = { Text("${bakim.baslik} kaydını silmek istediğinize emin misiniz?") },
            confirmButton = { Button(onClick = { onSil(bakim); silinecekBakim = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Sil") } },
            dismissButton = { OutlinedButton(onClick = { silinecekBakim = null }) { Text("Vazgeç") } }
        )
    }
}

@Composable
fun AylikMasrafEkrani(yakitlar: List<Yakit>, bakimlar: List<Bakim>) {
    val aylar = remember(yakitlar, bakimlar) {
        val grupMap = mutableMapOf<String, Pair<Double, Double>>()
        yakitlar.forEach {
            val key = formatAyYil(it.tarih)
            val mevcut = grupMap[key] ?: Pair(0.0, 0.0)
            grupMap[key] = Pair(mevcut.first + it.tl, mevcut.second)
        }
        bakimlar.forEach {
            val key = formatAyYil(it.tarih)
            val mevcut = grupMap[key] ?: Pair(0.0, 0.0)
            grupMap[key] = Pair(mevcut.first, mevcut.second + it.tutar)
        }
        grupMap.map { (ay, cift) -> Triple(ay, cift.first, cift.second) }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text("Aylık Harcamalar", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))

        if (aylar.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz aylık döküm bulunmuyor.", color = MaterialTheme.colorScheme.secondary)
            }
        } else {
            LazyColumn {
                items(aylar) { (ay, yakitTl, bakimTl) ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(ay, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text("${(yakitTl + bakimTl).round(2)} TL", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("⛽ Yakıt: ${yakitTl.round(2)} TL | 🔧 Bakım: ${bakimTl.round(2)} TL", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}