package gg.seven.scanner

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import gg.seven.scanner.core.ScanDocument
import java.io.File
import java.util.UUID

/*
 * Debug builds only (iOS -scanner.seed / -scanner.demo): a small believable library for the screens
 * tour, store pictures and tests. Launch with `--ez scanner.demo true` or `--ez scanner.seed true`
 * on an empty library. The text is filled in directly so the tour doesn't wait for recognition.
 * Grants nothing: access still comes only from the trial or the unlock.
 */
object Demo {
    fun fromIntent(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val store = DocumentStore(File(context.filesDir, ScannerModel.LIBRARY))
        if (intent.getBooleanExtra("scanner.reset", false)) File(context.filesDir, ScannerModel.LIBRARY).deleteRecursively()
        if (store.readLibrary().documents.isNotEmpty() || store.readLibrary(true).documents.isNotEmpty()) return
        when {
            intent.getBooleanExtra("scanner.demo", false) -> demo(context, store)
            intent.getBooleanExtra("scanner.seed", false) -> seed(context, store)
        }
    }

    private val samples =
        listOf(
            Sample("Café receipt", listOf("NORTH STREET CAFÉ\n13 Nov 2026  08:42\n\nFlat white        4.20\nAlmond croissant  3.80\n\nTotal             8.00\nCard ending 4417"), false, 0.05, null),
            Sample(
                "Apartment lease",
                listOf(
                    "RESIDENTIAL LEASE AGREEMENT\n\nThis lease is made between the landlord and the tenant for the property at 14 Harbour Road, Unit 3B.\n\nTerm: 12 months from 1 December 2026.",
                    "2. RENT AND DEPOSIT\n\nMonthly rent is due on the first day of each month. A deposit equal to one month of rent is held for the term.",
                    "3. SIGNATURES\n\nLandlord ______________\n\nTenant ______________\n\nDate 10 November 2026",
                ),
                true,
                3.0,
                "Signed copy. Original in the blue folder.",
            ),
            Sample("Pharmacy receipt", listOf("GREEN CROSS PHARMACY\n11 Nov 2026\n\nVitamin D 1000 IU   9.50\nSaline spray        6.25\n\nTotal              15.75"), false, 2.0, null),
            Sample(
                "Tax form 2026",
                listOf("INCOME STATEMENT 2026\n\nEmployer: Northwind Studio\nGross pay          52,400\nTax withheld       11,230", "SUPPLEMENTARY SCHEDULE\n\nCharitable gifts     420\nWork tools           610"),
                true,
                9.0,
                "For the accountant, due end of January.",
            ),
            Sample("Lemon cake recipe", listOf("LEMON CAKE\n\n200 g flour\n180 g sugar\n3 eggs\n2 lemons, zest and juice\n120 g butter\n\nBake 40 minutes at 175 °C."), false, 16.0, null),
            Sample("Washer warranty", listOf("LIMITED WARRANTY\n\nModel WM-7100\nSerial 7Q2-88140\nPurchased 2 March 2025\n\nCovered for 3 years from purchase."), false, 45.0, null),
        )

    private data class Sample(val title: String, val pages: List<String>, val favorite: Boolean, val days: Double, val note: String?)

    fun demo(context: Context, store: DocumentStore) {
        val staging = File(context.cacheDir, "ScannerDemo").apply { mkdirs() }
        try {
            for (sample in samples) {
                val images = sample.pages.map { text -> File(staging, "${UUID.randomUUID()}.jpg").also { Processing.normalize(page(text), it) } }
                val created = DocumentStore.nowReference() - sample.days * 86_400
                val doc = store.create(sample.title, images, sample.pages, created)
                store.save(doc.copy(favorite = sample.favorite, note = sample.note))
            }
            val bill = "CITY POWER & WATER\nAugust 2026\n\nAmount due  64.10"
            val old = store.create("Old utility bill", listOf(File(staging, "bill.jpg").also { Processing.normalize(page(bill), it) }), listOf(bill))
            store.delete(old)
        } finally {
            staging.deleteRecursively()
        }
    }

    /** Two synthetic receipt pages without text, so recognition runs on them (iOS -scanner.seed). */
    fun seed(context: Context, store: DocumentStore): ScanDocument {
        val staging = File(context.cacheDir, "ScannerDemo").apply { mkdirs() }
        try {
            val images = (1..2).map { n -> File(staging, "$n.jpg").also { Processing.normalize(page("SEVENS SCANNER\nReceipt page $n\nCoffee 120\nTotal 120", plain = true), it) } }
            return store.create("Sample receipt", images, listOf("", ""))
        } finally {
            staging.deleteRecursively()
        }
    }

    /** A plain printed page: a bold first line, a rule, then body text, on white. */
    fun page(text: String, plain: Boolean = false): Bitmap {
        val bitmap = createBitmap(600, 800)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val lines = text.split("\n")
        val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (plain) 32f else 26f
            typeface = if (plain) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
        }
        val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(31, 31, 31)
            textSize = if (plain) 32f else 19f
            typeface = if (plain) Typeface.DEFAULT else Typeface.MONOSPACE
        }
        canvas.drawText(lines[0], 48f, 90f, bold)
        if (!plain) canvas.drawRect(48f, 104f, 552f, 106f, Paint().apply { color = Color.rgb(51, 51, 51) })
        var y = if (plain) 140f else 150f
        for (line in lines.drop(1)) {
            canvas.drawText(line, 48f, y, body)
            y += body.textSize + if (plain) 14f else 8f
        }
        return bitmap
    }
}
