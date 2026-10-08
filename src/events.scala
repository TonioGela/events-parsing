import cats.syntax.all.*
import io.circe.*
import io.circe.parser.parse
import scala.scalajs.js

case class Event(
    meetupName: String,
    url: String,
    date: String,
    dateTo: Option[String],
    title: String,
    venue: String
) derives Codec.AsObject

case class Sources(
    meetup: List[String],
    luma: List[String],
    eventbrite: List[String],
    custom: List[Event]
)

object Sources:
  given Decoder[Sources] = c =>
    (
      c.getOrElse[List[String]]("meetup")(Nil),
      c.getOrElse[List[String]]("luma")(Nil),
      c.getOrElse[List[String]]("eventbrite")(Nil),
      c.getOrElse[List[Event]]("custom")(Nil)
    ).mapN(Sources.apply)

// Meetup, Luma and Eventbrite are all Next.js apps that embed their page state here.
private val NextData =
  """(?s)<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""".r

def nextData(html: String): Either[Throwable, ACursor] =
  NextData
    .findFirstMatchIn(html)
    .toRight(Exception("no __NEXT_DATA__ in page"))
    .flatMap(m => parse(m.group(1)))
    .map(_.hcursor.downField("props").downField("pageProps"))

// The group's events page holds its Apollo cache: a flat map of "Type:id" -> object.
def meetup(html: String): Either[Throwable, List[Event]] = for
  props <- nextData(html)
  state <- props.get[JsonObject]("__APOLLO_STATE__")
  entries = state.values.toList.map(_.hcursor)
  ofType = (t: String) =>
    entries.filter(_.get[String]("__typename").contains(t))
  group <- ofType("Group").headOption
    .toRight(Exception("no Group"))
    .flatMap(_.get[String]("name"))
  events <- ofType("Event")
    .filter(_.get[String]("status").contains("ACTIVE"))
    .traverse { e =>
      val venue = e
        .downField("venue")
        .get[String]("__ref")
        .flatMap(ref => state(ref).toRight(Exception(ref)))
        .flatMap(_.hcursor.get[String]("name"))
      (
        e.get[String]("eventUrl"),
        e.get[String]("dateTime"),
        e.get[Option[String]]("endTime"),
        e.get[String]("title")
      )
        .mapN(Event(group, _, _, _, _, venue.getOrElse("")))
    }
yield events

def luma(html: String): Either[Throwable, List[Event]] = for
  props <- nextData(html)
  data = props.downField("initialData").downField("data")
  calendar <- data.downField("calendar").get[String]("name")
  entries <- data.downField("upcoming").get[List[Json]]("entries")
  events <- entries.traverse { entry =>
    val e = entry.hcursor.downField("event")
    val venue = e.downField("geo_address_info").get[String]("address")
    (
      e.get[String]("url"),
      e.get[String]("start_at"),
      e.get[Option[String]]("end_at"),
      e.get[String]("name")
    )
      .mapN((slug, start, end, title) =>
        Event(
          calendar,
          s"https://luma.com/$slug",
          start,
          end,
          title,
          venue.getOrElse("")
        )
      )
  }
yield events

def eventbrite(html: String): Either[Throwable, List[Event]] = for
  props <- nextData(html)
  organizer <- props.downField("organizer").get[String]("name")
  upcoming <- props.get[List[Json]]("upcomingEvents")
  events <- upcoming
    .map(_.hcursor)
    .filterNot(_.get[Boolean]("is_cancelled").contains(true))
    .traverse { e =>
      val venue = e
        .downField("primary_venue")
        .get[String]("name")
        .toOption
        .orElse(
          Option
            .when(e.get[Boolean]("is_online_event").contains(true))("Online")
        )
      for
        tz <- e.get[String]("timezone")
        at = (d: String, t: String) =>
          (e.get[String](d), e.get[String](t))
            .mapN((d, t) => toUtc(s"${d}T$t", tz))
        start <- at("start_date", "start_time")
        end <- at("end_date", "end_time")
        url <- e.get[String]("url")
        title <- e.get[String]("name")
      yield Event(organizer, url, start, Some(end), title, venue.getOrElse(""))
    }
yield events

def toUtc(local: String, tz: String): String =
  val guess = js.Date.parse(s"${local}Z")
  val shown = js.Date.parse(s"${localDateTime(guess, tz).replace(' ', 'T')}Z")
  new js.Date(guess - (shown - guess)).toISOString()

// sv-SE formats as "yyyy-MM-dd HH:mm:ss".
private def localDateTime(millis: Double, tz: String): String =
  new js.Date(millis)
    .asInstanceOf[js.Dynamic]
    .toLocaleString("sv-SE", js.Dynamic.literal(timeZone = tz))
    .asInstanceOf[String]

private def localDay(iso: String, tz: String): String =
  localDateTime(js.Date.parse(iso), tz).take(10)

def normalize(events: List[Event], tz: String, now: Double): List[Event] =
  val today = localDateTime(now, tz).take(10)
  events
    .filter { e =>
      val end = e.dateTo.getOrElse(e.date)
      !js.Date.parse(end).isNaN && localDay(end, tz) >= today
    }
    .map(e =>
      e.copy(dateTo = e.dateTo.filter(localDay(_, tz) != localDay(e.date, tz)))
    )
    .sortBy(e => js.Date.parse(e.date))
