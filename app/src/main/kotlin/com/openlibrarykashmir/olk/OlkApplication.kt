package com.openlibrarykashmir.olk

import android.app.Application
import com.openlibrarykashmir.olk.core.data.dataModule
import com.openlibrarykashmir.olk.di.appModule
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.logger.Level

class OlkApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidLogger(if (BuildConfig.DEBUG) Level.INFO else Level.ERROR)
            androidContext(this@OlkApplication)
            modules(
                dataModule(
                    supabaseUrl = BuildConfig.SUPABASE_URL,
                    supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
                    websiteUrl = BuildConfig.WEBSITE_URL,
                ),
                appModule,
            )
        }
    }
}
