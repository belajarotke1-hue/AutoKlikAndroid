package com.custom.autoklik

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    private lateinit var etBx: EditText
    private lateinit var etBy: EditText
    private lateinit var etSx: EditText
    private lateinit var etSy: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView

    private val jadwalAktif = mutableMapOf<String, Pair<String, String>>() // "HH:mm:ss" -> (huruf, jamAsli)
    private var sedangBerjalan = false
    private val handler = Handler(Looper.getMainLooper())
    private val formatDetik = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val pilihFileLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { muatFileJadwal(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("AutoKlikPrefs", MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val btnIzin = Button(this).apply {
            text = "1. Aktifkan Izin Aksesibilitas"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        etBx = EditText(this).apply {
            hint = "Koordinat B - X (contoh: 500)"
            setText(prefs.getString("bx", ""))
        }
        etBy = EditText(this).apply {
            hint = "Koordinat B - Y (contoh: 300)"
            setText(prefs.getString("by", ""))
        }
        etSx = EditText(this).apply {
            hint = "Koordinat S - X (contoh: 700)"
            setText(prefs.getString("sx", ""))
        }
        etSy = EditText(this).apply {
            hint = "Koordinat S - Y (contoh: 300)"
            setText(prefs.getString("sy", ""))
        }

        val btnPilihFile = Button(this).apply {
            text = "2. Pilih File daftar.txt"
            setOnClickListener {
                simpanKoordinat()
                pilihFileLauncher.launch("text/plain")
            }
        }

        val btnMulai = Button(this).apply {
            text = "3. Mulai / Hentikan Jadwal"
            setOnClickListener {
                simpanKoordinat()
                if (!sedangBerjalan) mulaiJadwal() else hentikanJadwal()
            }
        }

        tvStatus = TextView(this).apply {
            text = "Status: Menunggu file daftar.txt"
            textSize = 15f
            setPadding(0, 20, 0, 10)
        }

        tvLog = TextView(this).apply {
            text = ""
            textSize = 13f
        }

        val scrollView = ScrollView(this).apply {
            addView(tvLog)
        }

        layout.addView(btnIzin)
        layout.addView(etBx)
        layout.addView(etBy)
        layout.addView(etSx)
        layout.addView(etSy)
        layout.addView(btnPilihFile)
        layout.addView(btnMulai)
        layout.addView(tvStatus)
        layout.addView(scrollView)

        setContentView(layout)
    }

    private fun simpanKoordinat() {
        getSharedPreferences("AutoKlikPrefs", MODE_PRIVATE).edit().apply {
            putString("bx", etBx.text.toString())
            putString("by", etBy.text.toString())
            putString("sx", etSx.text.toString())
            putString("sy", etSy.text.toString())
            apply()
        }
    }

    private fun muatFileJadwal(uri: Uri) {
        jadwalAktif.clear()
        val sekarang = LocalTime.now()
        val logBuilder = StringBuilder()

        contentResolver.openInputStream(uri)?.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).forEachLine { baris ->
                val bagian = baris.trim().lowercase().split("\\s+".toRegex())
                if (bagian.size == 2 && (bagian[1] == "b" || bagian[1] == "s")) {
                    val jamAsliStr = bagian[0].replace(".", ":")
                    val huruf = bagian[1]
                    try {
                        val parts = jamAsliStr.split(":")
                        val jamTarget = LocalTime.of(parts[0].toInt(), parts[1].toInt(), 0)
                        val waktuEksekusi = jamTarget.minusSeconds(1) // Maju 2 detik

                        if (!waktuEksekusi.isBefore(sekarang)) {
                            val kunci = waktuEksekusi.format(formatDetik)
                            jadwalAktif[kunci] = Pair(huruf, jamAsliStr)
                            logBuilder.append("• Jadwal $jamAsliStr -> Eksekusi $kunci (${huruf.uppercase()})\n")
                        } else {
                            logBuilder.append("• [Lewat] $jamAsliStr\n")
                        }
                    } catch (_: Exception) {}
                }
            }
        }
        tvStatus.text = "Status: ${jadwalAktif.size} jadwal aktif dimuat."
        tvLog.text = logBuilder.toString()
    }

    private val pengecekWaktu = object : Runnable {
        override fun run() {
            if (!sedangBerjalan) return

            val waktuSekarang = LocalTime.now().format(formatDetik)
            if (jadwalAktif.containsKey(waktuSekarang)) {
                val (huruf, jamAsli) = jadwalAktif.remove(waktuSekarang)!!
                val x = if (huruf == "b") etBx.text.toString().toFloatOrNull() else etSx.text.toString().toFloatOrNull()
                val y = if (huruf == "b") etBy.text.toString().toFloatOrNull() else etSy.text.toString().toFloatOrNull()

                if (x != null && y != null && AutoClickService.instance != null) {
                    AutoClickService.instance?.klikLayar(x, y)
                    tvLog.append("\n[$waktuSekarang] BERHASIL klik ${huruf.uppercase()} ($x, $y) [Jadwal $jamAsli]")
                } else {
                    tvLog.append("\n[$waktuSekarang] GAGAL: Cek izin aksesibilitas atau angka koordinat!")
                }

                if (jadwalAktif.isEmpty()) {
                    hentikanJadwal()
                    tvStatus.text = "Status: Semua jadwal hari ini selesai!"
                    return
                }
            }
            handler.postDelayed(this, 50) // Cek setiap 50ms agar presisi
        }
    }

    private fun mulaiJadwal() {
        if (AutoClickService.instance == null) {
            Toast.makeText(this, "Aktifkan Izin Aksesibilitas dulu di tombol nomor 1!", Toast.LENGTH_LONG).show()
            return
        }
        if (jadwalAktif.isEmpty()) {
            Toast.makeText(this, "Pilih file daftar.txt yang memiliki jadwal aktif dulu!", Toast.LENGTH_SHORT).show()
            return
        }
        sedangBerjalan = true
        tvStatus.text = "Status: BERJALAN (Menunggu jadwal...)"
        handler.post(pengecekWaktu)
    }

    private fun hentikanJadwal() {
        sedangBerjalan = false
        handler.removeCallbacks(pengecekWaktu)
        tvStatus.text = "Status: DIHENTIKAN"
    }
}
