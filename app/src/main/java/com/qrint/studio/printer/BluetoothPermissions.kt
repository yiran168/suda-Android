package com.qrint.studio.printer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object BluetoothPermissions {
    fun required(scan: Boolean): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> buildList {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (scan) add(Manifest.permission.BLUETOOTH_SCAN)
        }.toTypedArray()
        scan -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        else -> emptyArray()
    }

    fun has(context: Context, scan: Boolean): Boolean = required(scan).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
