package dev.lumen.sample.data

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** https://pokeapi.co — public, no key, real JSON for the Network panel. */
interface PokeApi {
  @GET("pokemon")
  suspend fun list(@Query("limit") limit: Int, @Query("offset") offset: Int = 0): PokemonPage

  @GET("pokemon/{name}")
  suspend fun pokemon(@Path("name") name: String): Pokemon
}

data class PokemonPage(val count: Int, val results: List<NamedResource>)

data class NamedResource(val name: String, val url: String)

data class Pokemon(
  val id: Int,
  val name: String,
  val height: Int,
  val weight: Int,
  val types: List<TypeSlot>,
) {
  val typeNames: List<String> get() = types.map { it.type.name }
}

data class TypeSlot(val slot: Int, val type: NamedResource)
