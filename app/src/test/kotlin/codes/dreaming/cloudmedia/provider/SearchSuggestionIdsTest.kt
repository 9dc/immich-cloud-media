package codes.dreaming.cloudmedia.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSuggestionIdsTest {
  @Test
  fun `text suggestion resolves to smart search`() {
    val id = SearchSuggestionIds.forText("  somtam  ")

    assertEquals(SearchTarget.Text("somtam"), SearchSuggestionIds.resolve(id, null))
  }

  @Test
  fun `person suggestion resolves to person search`() {
    val id = SearchSuggestionIds.forPerson("person-id")

    assertEquals(SearchTarget.Person("person-id"), SearchSuggestionIds.resolve(id, "ignored"))
  }

  @Test
  fun `unknown suggestion uses fallback text`() {
    assertEquals(
      SearchTarget.Text("somtam"),
      SearchSuggestionIds.resolve("unknown", " somtam ")
    )
  }

  @Test
  fun `old person suggestion remains compatible without fallback`() {
    assertEquals(
      SearchTarget.Person("old-person-id"),
      SearchSuggestionIds.resolve("old-person-id", null)
    )
  }
}
