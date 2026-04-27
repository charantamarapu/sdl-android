package com.sdl.grantha.di

import android.content.Context
import com.sdl.grantha.BuildConfig
import com.sdl.grantha.data.crypto.SdlCryptoManager
import com.sdl.grantha.data.download.GranthaDownloadManager
import com.sdl.grantha.data.local.GranthaDao
import com.sdl.grantha.data.local.GranthaDatabase
import com.sdl.grantha.data.remote.MobileCatalogApi
import com.sdl.grantha.data.repository.GranthaRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
            else HttpLoggingInterceptor.Level.NONE
        }
        
        // Add cache to support ETags and 304 Not Modified
        val cacheSize = 10 * 1024 * 1024L // 10 MB
        val cache = okhttp3.Cache(context.cacheDir, cacheSize)

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .cache(cache)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit {
        return Retrofit.Builder()
            .baseUrl(BuildConfig.SERVER_URL + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    @Provides
    @Singleton
    fun provideMobileCatalogApi(retrofit: Retrofit): MobileCatalogApi {
        return retrofit.create(MobileCatalogApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GranthaDatabase {
        return GranthaDatabase.getDatabase(context)
    }

    @Provides
    @Singleton
    fun provideGranthaDao(database: GranthaDatabase): GranthaDao {
        return database.granthaDao()
    }

    @Provides
    @Singleton
    fun provideSdlCryptoManager(): SdlCryptoManager {
        return SdlCryptoManager()
    }

    @Provides
    @Singleton
    fun provideGranthaDownloadManager(
        @ApplicationContext context: Context,
        api: MobileCatalogApi,
        dao: GranthaDao
    ): GranthaDownloadManager {
        return GranthaDownloadManager(context, api, dao)
    }

    @Provides
    @Singleton
    fun provideGranthaRepository(
        api: MobileCatalogApi,
        dao: GranthaDao,
        cryptoManager: SdlCryptoManager,
        downloadManager: GranthaDownloadManager,
        @ApplicationContext context: Context
    ): GranthaRepository {
        return GranthaRepository(api, dao, cryptoManager, downloadManager, context)
    }
}
