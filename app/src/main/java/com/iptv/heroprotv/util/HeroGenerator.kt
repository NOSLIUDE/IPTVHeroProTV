package com.iptv.heroprotv.util

import kotlin.random.Random

object HeroGenerator {
    private val marvelHeroes = listOf(
        "Homem de Ferro", "Capitão América", "Thor", "Hulk", "Viúva Negra",
        "Homem-Aranha", "Doutor Estranho", "Pantera Negra", "Capitã Marvel",
        "Feiticeira Escarlate", "Visão", "Falcão", "Soldado Invernal",
        "Gavião Arqueiro", "Star-Lord", "Gamora", "Rocket", "Groot",
        "Drax", "Mantis", "Shang-Chi", "Ms. Marvel", "Cavaleiro da Lua",
        "Mulher-Hulk", "Loki", "Kate Bishop", "Yelena", "Sam Wilson"
    )

    private val dcHeroes = listOf(
        "Batman", "Superman", "Mulher-Maravilha", "Flash", "Aquaman",
        "Ciborgue", "Lanterna Verde", "Arqueiro Verde", "Canário Negro",
        "Supergirl", "Batwoman", "Shazam", "Adão Negro", "Besouro Azul",
        "Zatanna", "Constantine", "Asa Noturna", "Estelar", "Mutano",
        "Ravena", "Mulher-Gato", "Arlequina", "Pacificador", "Caçador de Marte"
    )

    data class GeneratedHero(val name: String, val universe: String)

    fun generate(): GeneratedHero {
        val universe = if (Random.nextBoolean()) "marvel" else "dc"
        val list = if (universe == "marvel") marvelHeroes else dcHeroes
        val base = list[Random.nextInt(list.size)]
        val suffix = Random.nextInt(1000, 10000)
        return GeneratedHero("${base}_$suffix", universe)
    }
}
