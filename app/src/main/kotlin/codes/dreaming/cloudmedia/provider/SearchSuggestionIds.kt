package codes.dreaming.cloudmedia.provider

internal sealed interface SearchTarget {
  data class Text(val query: String) : SearchTarget
  data class Person(val personId: String) : SearchTarget
}

internal object SearchSuggestionIds {
  // Version this ID when search behavior changes. Android persists completed
  // searches by media-set ID, including an empty result caused by a timeout.
  private const val TEXT_PREFIX = "immich:v2:text:"
  private const val PERSON_PREFIX = "immich:person:"

  fun forText(query: String): String = TEXT_PREFIX + query.trim()

  fun forPerson(personId: String): String = PERSON_PREFIX + personId

  fun resolve(mediaSetId: String, fallbackSearchText: String?): SearchTarget {
    return when {
      mediaSetId.startsWith(TEXT_PREFIX) ->
        SearchTarget.Text(mediaSetId.removePrefix(TEXT_PREFIX))

      mediaSetId.startsWith(PERSON_PREFIX) ->
        SearchTarget.Person(mediaSetId.removePrefix(PERSON_PREFIX))

      !fallbackSearchText.isNullOrBlank() ->
        SearchTarget.Text(fallbackSearchText.trim())

      // Keep suggestions from an older installed version working.
      else -> SearchTarget.Person(mediaSetId)
    }
  }
}
