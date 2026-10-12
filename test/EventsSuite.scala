import io.circe.parser.decode
import scala.scalajs.js

class EventsSuite extends munit.FunSuite:

  def fixture(name: String): String =
    fs.readFileSync(s"test/fixtures/$name.html", "utf8")

  test("meetup: upcoming events with group name and venue") {
    assertEquals(
      meetup(fixture("meetup")),
      Right(
        List(
          Event(
            "Nix Milano",
            "https://www.meetup.com/nix-milano/events/316849991/",
            "2026-10-17T09:30:00+02:00",
            Some("2026-10-17T17:30:00+02:00"),
            "Nix Unconference #12",
            "rev.ng Labs",
            Some("Milan")
          )
        )
      )
    )
  }

  test("luma: upcoming calendar entries") {
    assertEquals(
      luma(fixture("luma")),
      Right(
        List(
          Event(
            "Doctolib AI Community",
            "https://luma.com/pn09gzkc",
            "2026-10-22T16:30:00.000Z",
            Some("2026-10-22T19:30:00.000Z"),
            "Milan | Claude Code for Developers",
            "Via Vittor Pisani, 19",
            Some("Milano")
          )
        )
      )
    )
  }

  test(
    "eventbrite: organizer's upcoming events, wall-clock times converted to UTC"
  ) {
    assertEquals(
      eventbrite(fixture("eventbrite")),
      Right(
        List(
          Event(
            "Assintel - Associazione Italiana Imprese Digitali",
            "https://www.eventbrite.com/e/contest-assintel-presenta-la-tua-innovazione-tickets-2002787273649",
            "2026-10-19T08:00:00.000Z",
            Some("2026-10-19T08:00:00.000Z"),
            "Contest Assintel: presenta la tua innovazione",
            "Corso Venezia, 47",
            Some("Milano")
          )
        )
      )
    )
  }

  test("a page without __NEXT_DATA__ is an error, not an empty list") {
    assert(meetup("<html></html>").isLeft)
    assert(luma("<html></html>").isLeft)
    assert(eventbrite("<html></html>").isLeft)
  }

  test("toUtc honours DST") {
    assertEquals(
      toUtc("2026-01-15T10:00:00", "Europe/Rome"),
      "2026-01-15T09:00:00.000Z"
    )
    assertEquals(
      toUtc("2026-07-15T10:00:00", "Europe/Rome"),
      "2026-07-15T08:00:00.000Z"
    )
  }

  val now = js.Date.parse("2026-10-10T00:00:00Z")
  def at(date: String, dateTo: Option[String] = None) =
    Event("g", s"$date$dateTo", date, dateTo, date, "v")

  test(
    "normalize drops events ended before today, keeps today's, sorts by start"
  ) {
    val past = at("2026-10-01T18:00:00+02:00")
    // now is 02:00 on Oct 10 in Rome.
    val lastNight = at("2026-10-09T23:30:00+02:00")
    val endedToday = at("2026-10-10T00:30:00+02:00")
    val invalid = at("not a date")
    val running =
      at("2026-10-08T09:00:00+02:00", Some("2026-10-12T18:00:00+02:00"))
    val later = at("2026-11-01T18:00:00+01:00")
    val sooner = at("2026-10-20T16:00:00Z")
    assertEquals(
      normalize(
        List(later, past, lastNight, invalid, sooner, endedToday, running),
        "Europe/Rome",
        now
      ),
      List(running, endedToday, sooner, later)
    )
  }

  test(
    "normalize keeps dateTo only for multi-day events, in the given timezone"
  ) {
    val sameDay =
      at("2026-10-20T18:00:00+02:00", Some("2026-10-20T21:00:00+02:00"))
    // 23:30Z is already the next day in Rome.
    val crossesMidnightInRome =
      at("2026-10-20T20:00:00Z", Some("2026-10-20T23:30:00Z"))
    assertEquals(
      normalize(List(sameDay, crossesMidnightInRome), "Europe/Rome", now)
        .map(_.dateTo),
      List(None, Some("2026-10-20T23:30:00Z"))
    )
  }

  test("normalize keeps only the first event for each url") {
    val e = at("2026-10-20T18:00:00+02:00")
    assertEquals(
      normalize(List(e, e.copy(title = "dup")), "Europe/Rome", now),
      List(e)
    )
  }

  test("meetupUrl drops the locale segment") {
    val url = "https://www.meetup.com/master-the-vibe-milan/events/315691552/"
    assertEquals(
      meetupUrl("https://www.meetup.com/it-IT/master-the-vibe-milan/events/315691552/"),
      url
    )
    assertEquals(meetupUrl(url), url)
  }

  test(
    "sources: every key is optional, custom events have an optional dateTo"
  ) {
    val json =
      """{"luma": ["https://luma.com/x"], "custom": [{"meetupName": "m", "url": "u", "date": "d", "title": "t", "venue": ""}]}"""
    assertEquals(
      decode[Sources](json),
      Right(
        Sources(
          Nil,
          List("https://luma.com/x"),
          Nil,
          List(Event("m", "u", "d", None, "t", ""))
        )
      )
    )
  }
