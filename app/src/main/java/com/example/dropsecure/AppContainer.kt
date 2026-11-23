package com.example.dropsecure

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.dropsecure.data.AppDatabase
import com.example.dropsecure.io.FileSaver
import com.example.dropsecure.manager.TransferManager
import com.example.dropsecure.settings.AppSettings

/** What the user picked on the Send screen, carried to the discovery screen. */
class SendDraft {
    var uri by mutableStateOf<Uri?>(null)
    var name by mutableStateOf("")
    var size by mutableStateOf(0L)
    /** Set when resuming an interrupted transfer from History. */
    var resumeTransferId by mutableStateOf<String?>(null)

    fun clear() { uri = null; name = ""; size = 0; resumeTransferId = null }
}

class AppContainer(app: Application) {
    val settings = AppSettings(app)
    val database = AppDatabase.build(app)
    val saver = FileSaver(app, settings)
    val manager = TransferManager(app, database.transferDao(), settings, saver)
    val draft = SendDraft()
}

class DropSecureApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.manager.onAppStart()
    }
}

fun Context.container(): AppContainer = (applicationContext as DropSecureApp).container
