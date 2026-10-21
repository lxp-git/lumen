package dev.lumen.sample

import android.app.Application
import androidx.room.Room
import dev.lumen.sample.data.PokeApi
import dev.lumen.sample.data.SampleDatabase
import dev.lumen.sample.data.Settings
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.create
import java.util.concurrent.TimeUnit

/** Hand-rolled service graph: one OkHttpClient shared by Retrofit, WebSocket and Socket.IO. */
class AppGraph(app: Application) {
  val okHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
      .connectTimeout(15, TimeUnit.SECONDS)
      .readTimeout(30, TimeUnit.SECONDS)
      .pingInterval(20, TimeUnit.SECONDS)
      .addInterceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", "LumenSample").build())
      }
      .build()
  }

  val pokeApi: PokeApi by lazy {
    Retrofit.Builder()
      .baseUrl("https://pokeapi.co/api/v2/")
      .client(okHttp)
      .addConverterFactory(GsonConverterFactory.create())
      .build()
      .create()
  }

  val database: SampleDatabase by lazy {
    Room.databaseBuilder(app, SampleDatabase::class.java, "sample.db").build()
  }

  val settings: Settings by lazy { Settings(app) }
}
