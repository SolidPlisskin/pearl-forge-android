package com.solidplisskin.pearlforge.recordings

import com.solidplisskin.pearlforge.pipeline.SpeechLanguages
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which language a piece of text is in, worked out on the tablet (no AI, no cost), so the library
 * can label pearls in other languages and show their English versions.
 *
 * Android has nothing like the iPhone's NaturalLanguage framework that works the same on every
 * tablet, so the writing system (Hangul, kana, Cyrillic…) and each language's most common little
 * words decide it, which works the same everywhere. Text those can't settle (a few words, or a
 * language with no word list here) goes to Android's own language detector, where the tablet has one.
 */
object TextLanguage {
    /** The language you read: library titles and English versions use it. */
    const val YOURS = "en"

    /**
     * Android's own detector: a language code, "" when it can't tell, or null when it can't be asked
     * right now (it mustn't run on the main thread). Set when the app starts, on Android 10 and later.
     */
    @Volatile var systemDetector: ((String) -> String?)? = null

    /** Answers already worked out; "" for text whose language couldn't be told. */
    private val cache = ConcurrentHashMap<String, String>()

    /** The text's language code ("fr", "ko", "zh"), or null when it's too short or mixed to tell. */
    fun code(text: String): String? {
        val sample = text.take(400)
        if (sample.isBlank()) return null
        cache[sample]?.let { return it.ifEmpty { null } }
        val guess = WordLists.guess(sample)
        val answer = if (guess.isSure) guess.code else {
            val detector = systemDetector
            if (detector != null) {
                // Not now (the main thread): use the word lists' guess, and ask again off it later.
                val system = detector(sample) ?: return guess.code
                system.ifBlank { null } ?: guess.code
            } else guess.code
        }
        cache[sample] = answer ?: ""
        return answer
    }

    /** The text's language when it isn't yours: "Korean", "French". */
    fun other(text: String): String? {
        val code = code(text) ?: return null
        return if (code == YOURS) null else name(code)
    }

    fun isOther(text: String): Boolean = other(text) != null

    /** "Korean" for "ko". */
    fun name(code: String): String =
        SpeechLanguages.named(code)?.name ?: Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }
}

/**
 * Tells languages apart by writing system and by their most common short words ("the", "et",
 * "che", "und"…), which even a sentence or two is full of.
 */
internal object WordLists {
    data class Guess(val code: String?, val isSure: Boolean)

    private fun words(text: String) = text.split(" ").filter { it.isNotEmpty() }.toSet()

    /** The commonest words of the Latin-script languages your voice speaks. */
    private val common: Map<String, Set<String>> = mapOf(
        "en" to words("the and of to is in that it you was for on are with as i his they be at have this from or had by not but what all were we when your can said there an which she do how their if will up about out them then these so some her would make like him into time has more no could my than been who its now did get just me because think know don yeah um uh okay huh really very should well much us may our here why where want going say thing things"),
        "fr" to words("le la les des du de un une et est que qui pas ne je tu il elle nous vous ils elles ce cette ces dans pour sur avec au aux mais où donc très plus moi toi lui leur son sa ses mon ma mes ton ta tes notre votre être avoir fait comme si bien aussi tout tous était été ça alors hein ouais oui non parce quand peut sont ont avez suis es sommes êtes cela entre sans sous chez encore déjà après avant rien jamais toujours quoi pourquoi comment en y l j qu n c d"),
        "it" to words("il lo la i gli le di del della dei delle da dal in nel nella con su per tra fra un uno una e è che chi non mi ti si ci vi ne io tu lui lei noi voi loro mio mia tuo sua suo questo questa quello quella come ma anche perché sono sei siamo essere avere ho hai ha abbiamo hanno era fosse così molto più già allora bene va quel cosa ogni sempre mai dove quando se l c dell nell sull po"),
        "es" to words("el la los las un una unos unas de del y o que en es por para con no se su sus lo le les al como más pero ya muy también este esta esto ese esa eso yo tú él ella nosotros ellos mi me te nos porque cuando donde hay ser estar está están soy eres son fue era sea tiene tengo hace hacer todo nada algo bien sí después antes sin sobre entre hasta desde"),
        "pt" to words("o a os as um uma de do da dos das e é que em no na nos nas por para com não se seu sua ao à como mais mas já muito também este esta isso isto esse essa eu você ele ela nós eles meu minha me te porque quando onde há ser estar está sou são foi era tem tenho faz fazer tudo nada bem sim depois antes sem sobre entre até desde coisas acho"),
        "de" to words("der die das den dem des ein eine einen einem einer und oder ist sind war nicht ich du er sie es wir ihr mit von zu auf für an im in dass weil aber auch so wie als noch nur schon sehr mich mir dich sich mein dein sein kein keine wenn was wer wo hat habe haben wird werden kann muss bin bist aus bei nach über unter vor dinge glaube"),
        "nl" to words("de het een en van is dat die in op te met voor niet zijn ik je jij hij zij we wij ze er maar ook als aan om bij nog dan wat wie waar omdat heb hebben heeft was waren wordt kan moet geen mijn jouw haar ons hun dit deze naar uit over door ben denk dingen goed veel"),
        "pl" to words("i w z na do nie się to jest że a o jak ale co tak ja ty on ona my wy oni mnie mi go jej ich mój moja twój jego ten ta te tym tego czy już tylko przez dla od po przy bo gdy kiedy jestem są był była było mam ma może bardzo rzeczy dlatego później myślę"),
        "sv" to words("och att det som en ett är på för med av till den de inte jag du han hon vi ni om men så har var kan ska vill mig dig sig min din sin vår när då här där eller också bara nu sedan mycket saker tror känns"),
        "da" to words("og at det som en et er på for med af til den de ikke jeg du han hun vi i om men så har var kan skal vil mig dig sig min din sin vores når da her der eller også bare nu meget ting tror"),
        "fi" to words("ja on ei se että oli olla ovat minä sinä hän me te he mutta kun niin kuin myös vain jo nyt tämä tuo mitä mikä joka jos koska kanssa ole olen olet hyvin paljon asioita luulen"),
        "tr" to words("ve bir bu da de için ile ne çok ama gibi daha o ben sen biz siz onlar mi mı mu mü var yok değil olarak olan kadar sonra şey her çünkü bana beni benim onu işleri"),
        "ro" to words("și de la în un o că nu cu pe se este sunt a al ale ai din pentru mai dar sau ce care eu tu el ea noi voi ei ele mă îmi mi te foarte lucrurile cred fiindcă deoarece"),
        "cs" to words("a je se na v že to s z do o jsem jsi jsou být ale jak tak co já ty on ona my vy oni mě mi tě ten ta není nejsem protože když také jen už velmi věci myslím"),
        "sk" to words("a je sa na v že to s z do o som si sú byť ale ako tak čo ja ty on ona my vy oni ma mi ťa ten táto nie pretože keď tiež len už veľmi veci myslím"),
        "hr" to words("i je se na u da to s z za o sam si su biti ali kako tako što ja ti on ona mi vi oni me te ne nije jer kad kada također samo već jako vrlo stvari mislim"),
        "id" to words("dan yang di ke dari ini itu untuk dengan tidak ada saya aku kamu dia kami kita mereka akan juga karena sudah belum bisa adalah atau tetapi tapi seperti sangat hal merasa"),
        "ms" to words("dan yang di ke dari ini itu untuk dengan tidak ada saya aku kamu dia kami kita mereka akan juga kerana sudah belum boleh adalah atau tetapi seperti sangat perkara"),
        "fil" to words("ang ng mga sa na at ay si ni ko mo niya siya ako ikaw kami tayo sila hindi oo ito iyan iyon para kung dahil pero lang din rin po naman talaga"),
    )

    /** Letters only one of those languages uses, a small nudge when the words are close. */
    private val telltaleLetters: Map<Char, String> = buildMap {
        "ñ¿¡".forEach { put(it, "es") }
        "ãõ".forEach { put(it, "pt") }
        "ß".forEach { put(it, "de") }
        "œ".forEach { put(it, "fr") }
        "ąęłńśźż".forEach { put(it, "pl") }
        "ğı".forEach { put(it, "tr") }
        "ășțţ".forEach { put(it, "ro") }
        "řůě".forEach { put(it, "cs") }
        "ľĺŕ".forEach { put(it, "sk") }
        "đ".forEach { put(it, "hr") }
        "æø".forEach { put(it, "da") }
    }

    fun guess(text: String): Guess {
        script(text)?.let { return Guess(it, isSure = true) }
        val scores = HashMap<String, Double>()
        for (word in text.lowercase().split(Regex("""[^\p{L}]+"""))) {
            if (word.isEmpty()) continue
            for ((code, list) in common) if (word in list) scores[code] = (scores[code] ?: 0.0) + 1
        }
        for (letter in text.lowercase()) telltaleLetters[letter]?.let { scores[it] = (scores[it] ?: 0.0) + 0.5 }
        val ranked = scores.entries.sortedByDescending { it.value }
        val best = ranked.firstOrNull() ?: return Guess(null, isSure = false)
        val second = ranked.getOrNull(1)?.value ?: 0.0
        if (best.value == second) return Guess(null, isSure = false)
        return Guess(best.key, isSure = best.value >= 2 && best.value >= second * 1.5)
    }

    /** The language a non-Latin writing system gives away, when most of the letters are in it. */
    private fun script(text: String): String? {
        var letters = 0
        val counts = HashMap<String, Int>()
        for (char in text) {
            if (!char.isLetter()) continue
            letters++
            val script = when (char.code) {
                in 0xAC00..0xD7AF, in 0x1100..0x11FF, in 0x3130..0x318F -> "hangul"
                in 0x3040..0x309F, in 0x30A0..0x30FF -> "kana"
                in 0x4E00..0x9FFF, in 0x3400..0x4DBF -> "han"
                in 0x0400..0x04FF -> "cyrillic"
                in 0x0370..0x03FF -> "greek"
                in 0x0600..0x06FF, in 0x0750..0x077F -> "arabic"
                in 0x0590..0x05FF -> "hebrew"
                in 0x0900..0x097F -> "devanagari"
                in 0x0B80..0x0BFF -> "tamil"
                in 0x0E00..0x0E7F -> "thai"
                else -> continue
            }
            counts[script] = (counts[script] ?: 0) + 1
        }
        if (letters == 0) return null
        fun share(script: String) = (counts[script] ?: 0).toDouble() / letters
        return when {
            share("hangul") >= 0.3 -> "ko"
            share("kana") >= 0.05 -> "ja"
            share("han") >= 0.3 -> "zh"
            share("cyrillic") >= 0.5 -> when {
                text.any { it in "ієїґІЄЇҐ" } -> "uk"
                text.contains('ъ') && text.none { it in "ыэё" } -> "bg"
                else -> "ru"
            }
            share("greek") >= 0.5 -> "el"
            share("arabic") >= 0.5 -> if (text.any { it in "پچژگ" }) "fa" else "ar"
            share("hebrew") >= 0.5 -> "he"
            share("devanagari") >= 0.5 -> "hi"
            share("tamil") >= 0.5 -> "ta"
            share("thai") >= 0.5 -> "th"
            else -> null
        }
    }
}
