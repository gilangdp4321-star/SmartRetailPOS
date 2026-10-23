package com.industri.smartpos

import java.text.NumberFormat
import java.util.Locale

class ProductBarcodeNotFoundException(val barcode: String) :
    Exception("Produk dengan barcode '$barcode' tidak ditemukan di database.")

class PaymentGatewayTimeoutException(val gatewayName: String) :
    Exception("Koneksi ke gateway '$gatewayName' mengalami batas waktu habis.")

class CashierLimitExceededException(val maxLimit: Double) :
    Exception("Transaksi melebihi limit batas kasir sebesar Rp ${NumberFormat.getNumberInstance(Locale.forLanguageTag("id-ID")).format(maxLimit)}.")
