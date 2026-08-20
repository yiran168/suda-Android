package com.qrint.studio

import android.app.Application
import com.qrint.ppocr.PpOcrMobile
import com.qrint.studio.data.CrashReportStore
import com.qrint.studio.data.LocalStore
import com.qrint.studio.data.RuntimeLogStore
import com.qrint.studio.printer.BluetoothPrinterManager

class QrintApplication : Application() {
    lateinit var printerManager: BluetoothPrinterManager
        private set
    lateinit var localStore: LocalStore
        private set
    lateinit var crashReports: CrashReportStore
        private set
    lateinit var runtimeLogs: RuntimeLogStore
        private set

    override fun onCreate() {
        super.onCreate()
        runtimeLogs = RuntimeLogStore(this).also { it.info("应用启动", "进程已初始化") }
        crashReports = CrashReportStore(this).also { it.install(); it.setStage("application-start") }
        printerManager = BluetoothPrinterManager(this, runtimeLogs)
        localStore = LocalStore(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        PpOcrMobile.onTrimMemory(level)
    }
}
