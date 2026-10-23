package com.industri.smartpos

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.NumberFormat
import java.util.Locale

class PosActivity : AppCompatActivity() {

    private lateinit var coordinatorLayout: CoordinatorLayout
    private lateinit var etBarcode: TextInputEditText
    private lateinit var etAmount: TextInputEditText
    private lateinit var rgSimulation: RadioGroup
    private lateinit var rbSuccess: RadioButton
    private lateinit var rbBarcodeError: RadioButton
    private lateinit var rbNetworkTimeout: RadioButton
    private lateinit var rbLimitExceeded: RadioButton
    private lateinit var btnProcessPayment: Button
    private lateinit var pbPosLoading: ProgressBar
    private lateinit var cardPosResult: MaterialCardView
    private lateinit var tvPosStatus: TextView
    private lateinit var tvPosDetails: TextView

    private var retryAttempt = 0
    private val maxRetryThreshold = 3
    private val supervisorPin = "1234"

    private val coroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Fatal error caught in CoroutineExceptionHandler: ${throwable.message}")
        runOnUiThread {
            updateUi(
                UiState.Error(
                    message = "Terjadi kesalahan sistem fatal: ${throwable.localizedMessage}",
                    canRetry = true,
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pos)

        initViews()

        btnProcessPayment.setOnClickListener {
            processTransaction(isSupervisorAuthorized = false)
        }
    }

    private fun initViews() {
        coordinatorLayout = findViewById(R.id.coordinatorLayout)
        etBarcode = findViewById(R.id.etBarcode)
        etAmount = findViewById(R.id.etAmount)
        rgSimulation = findViewById(R.id.rgSimulation)
        rbSuccess = findViewById(R.id.rbSuccess)
        rbBarcodeError = findViewById(R.id.rbBarcodeError)
        rbNetworkTimeout = findViewById(R.id.rbNetworkTimeout)
        rbLimitExceeded = findViewById(R.id.rbLimitExceeded)
        btnProcessPayment = findViewById(R.id.btnProcessPayment)
        pbPosLoading = findViewById(R.id.pbPosLoading)
        cardPosResult = findViewById(R.id.cardPosResult)
        tvPosStatus = findViewById(R.id.tvPosStatus)
        tvPosDetails = findViewById(R.id.tvPosDetails)
    }

    private fun processTransaction(isSupervisorAuthorized: Boolean) {
        val barcode = etBarcode.text?.toString()?.trim().orEmpty()
        val amount = etAmount.text?.toString()?.toDoubleOrNull() ?: 150000.0

        if (barcode.isEmpty()) {
            etBarcode.error = "Barcode tidak boleh kosong"
            return
        }

        updateUi(UiState.Loading)

        lifecycleScope.launch(Dispatchers.IO + coroutineExceptionHandler) {
            Timber.d("Memulai pemrosesan transaksi... Barcode: $barcode, Nominal: Rp $amount, Authorized: $isSupervisorAuthorized")

            val result = runCatching {
                simulatePaymentProcess(barcode, amount, isSupervisorAuthorized)
            }

            withContext(Dispatchers.Main) {
                result.onSuccess { transaction ->
                    retryAttempt = 0
                    Timber.i("Transaksi berhasil diproses: ${transaction.transactionId}")
                    updateUi(UiState.Success(transaction))
                }.onFailure { exception ->
                    Timber.w(exception, "Transaksi gagal diproses: ${exception.message}")
                    handleTransactionException(exception)
                }
            }
        }
    }

    private suspend fun simulatePaymentProcess(
        barcode: String,
        amount: Double,
        isSupervisorAuthorized: Boolean
    ): PosTransaction {
        delay(1200) // Simulasi delay jaringan / I/O

        val selectedRadioId = rgSimulation.checkedRadioButtonId

        when {
            selectedRadioId == R.id.rbBarcodeError -> {
                throw ProductBarcodeNotFoundException(barcode)
            }
            selectedRadioId == R.id.rbNetworkTimeout -> {
                throw PaymentGatewayTimeoutException("QRIS Bank Settlement")
            }
            selectedRadioId == R.id.rbLimitExceeded -> {
                if (!isSupervisorAuthorized) {
                    throw CashierLimitExceededException(10000000.0)
                }
            }
            (amount > 10000000.0) && !isSupervisorAuthorized -> {
                throw CashierLimitExceededException(10000000.0)
            }
        }

        val statusText = if (isSupervisorAuthorized) {
            "LUNAS (Otorisasi Supervisor)"
        } else {
            "LUNAS / SUCCESS"
        }

        return PosTransaction(
            transactionId = "TRX-${(100000..999999).random()}",
            barcode = barcode,
            productName = "Produk Retail POS Item",
            totalAmount = amount,
            status = statusText
        )
    }

    private fun handleTransactionException(exception: Throwable) {
        when (exception) {
            is CashierLimitExceededException -> {
                updateUi(UiState.Error(exception.message ?: "Limit Kasir Terlampaui", canRetry = false))
                showSupervisorPinDialog(exception)
            }
            is ProductBarcodeNotFoundException, is PaymentGatewayTimeoutException -> {
                val errorMessage = exception.message ?: "Terjadi kesalahan transaksi"
                updateUi(UiState.Error(errorMessage, canRetry = true))
                showErrorSnackbar(errorMessage)
            }
            else -> {
                val errorMessage = exception.localizedMessage ?: "Terjadi error yang tidak diketahui"
                updateUi(UiState.Error(errorMessage, canRetry = true))
                showErrorSnackbar(errorMessage)
            }
        }
    }

    private fun showErrorSnackbar(errorMessage: String) {
        val snackbar = Snackbar.make(coordinatorLayout, errorMessage, Snackbar.LENGTH_INDEFINITE)
        snackbar.setAction("COBA LAGI") {
            retryAttempt++
            Timber.w("Percobaan retry ke-$retryAttempt dari $maxRetryThreshold")

            if (retryAttempt >= maxRetryThreshold) {
                showFallbackCashPaymentDialog()
            } else {
                processTransaction(isSupervisorAuthorized = false)
            }
        }
        snackbar.show()
    }

    private fun showFallbackCashPaymentDialog() {
        AlertDialog.Builder(this)
            .setTitle("Batas Percobaan Terlampaui (3x)")
            .setMessage("Transaksi telah gagal sebanyak $retryAttempt kali berturut-turut. Apakah Anda ingin mengalihkan transaksi ke Pembayaran Tunai Manual (Fallback Mode)?")
            .setPositiveButton("Gunakan Tunai Manual") { dialog, _ ->
                dialog.dismiss()
                retryAttempt = 0
                val barcode = etBarcode.text?.toString()?.trim().orEmpty()
                val amount = etAmount.text?.toString()?.toDoubleOrNull() ?: 150000.0

                val fallbackTransaction = PosTransaction(
                    transactionId = "TRX-CASH-${(100000..999999).random()}",
                    barcode = barcode,
                    productName = "Produk Retail POS Item",
                    totalAmount = amount,
                    status = "LUNAS (Tunai Manual / Fallback Mode)"
                )
                Timber.i("Fallback Mode diaktifkan: Pembayaran Tunai Manual untuk ID ${fallbackTransaction.transactionId}")
                updateUi(UiState.Success(fallbackTransaction))
                Toast.makeText(this, "Berhasil dialihkan ke Pembayaran Tunai Manual!", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("Batal") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun showSupervisorPinDialog(exception: CashierLimitExceededException) {
        val pinInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Masukkan PIN Supervisor (Contoh: 1234)"
            setPadding(50, 30, 50, 30)
        }

        AlertDialog.Builder(this)
            .setTitle("Otorisasi Supervisor Diperlukan")
            .setMessage("${exception.message}\n\nSilakan masukkan PIN Supervisor untuk menyetujui transaksi ini:")
            .setView(pinInput)
            .setPositiveButton("Otorisasi") { dialog, _ ->
                dialog.dismiss()
                val enteredPin = pinInput.text.toString().trim()
                if (enteredPin == supervisorPin) {
                    Timber.i("Otorisasi Supervisor BERHASIL dengan PIN valid.")
                    Toast.makeText(this, "Otorisasi Supervisor Berhasil!", Toast.LENGTH_SHORT).show()
                    processTransaction(isSupervisorAuthorized = true)
                } else {
                    Timber.w("Otorisasi Supervisor GAGAL: PIN '$enteredPin' salah.")
                    Toast.makeText(this, "PIN Supervisor Salah! Transaksi Dibatalkan.", Toast.LENGTH_LONG).show()
                    updateUi(UiState.Error("Otorisasi Ditolak: PIN Supervisor Salah.", canRetry = false))
                }
            }
            .setNegativeButton("Batal") { dialog, _ ->
                dialog.dismiss()
                Timber.i("Otorisasi Supervisor dibatalkan oleh pengguna.")
                updateUi(UiState.Error("Transaksi Dibatalkan (Perlu Otorisasi Supervisor)", canRetry = false))
            }
            .setCancelable(false)
            .show()
    }

    private fun updateUi(state: UiState<PosTransaction>) {
        when (state) {
            is UiState.Idle -> {
                pbPosLoading.visibility = View.GONE
                cardPosResult.visibility = View.GONE
                btnProcessPayment.isEnabled = true
            }
            is UiState.Loading -> {
                pbPosLoading.visibility = View.VISIBLE
                cardPosResult.visibility = View.GONE
                btnProcessPayment.isEnabled = false
            }
            is UiState.Success -> {
                pbPosLoading.visibility = View.GONE
                btnProcessPayment.isEnabled = true
                cardPosResult.visibility = View.VISIBLE

                val transaction = state.data
                val formattedAmount = NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID"))
                    .format(transaction.totalAmount)

                tvPosStatus.text = "STATUS: TRANSAKSI BERHASIL"
                tvPosStatus.setTextColor(getColor(android.R.color.holo_green_dark))

                tvPosDetails.text = """
                    ID Transaksi : ${transaction.transactionId}
                    Barcode     : ${transaction.barcode}
                    Produk      : ${transaction.productName}
                    Total       : Rp $formattedAmount
                    Status      : ${transaction.status}
                """.trimIndent()
            }
            is UiState.Error -> {
                pbPosLoading.visibility = View.GONE
                btnProcessPayment.isEnabled = true
                cardPosResult.visibility = View.VISIBLE

                tvPosStatus.text = "STATUS: TRANSAKSI GAGAL"
                tvPosStatus.setTextColor(getColor(android.R.color.holo_red_dark))

                tvPosDetails.text = """
                    Pesan Error : ${state.message}
                    Bisa Retry  : ${if (state.canRetry) "Ya" else "Tidak"}
                    Attempt Retry: $retryAttempt / $maxRetryThreshold
                """.trimIndent()
            }
        }
    }
}
