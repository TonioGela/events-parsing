import io.circe.Printer
import io.circe.parser.decode
import io.circe.syntax.*
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.concurrent.JSExecutionContext
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.scalajs.js.annotation.*
import scala.util.{Failure, Success}

@js.native @JSGlobalScope
object Node extends js.Object:
  def fetch(url: String): js.Promise[Response] = js.native
  val process: js.Dynamic = js.native

@js.native
trait Response extends js.Object:
  val ok: Boolean = js.native
  val status: Int = js.native
  def text(): js.Promise[String] = js.native

@js.native @JSImport("node:fs", JSImport.Namespace)
object fs extends js.Object:
  def readFileSync(path: String, encoding: String): String = js.native
  def writeFileSync(path: String, data: String): Unit = js.native
  def mkdirSync(path: String, options: js.Object): Unit = js.native

@js.native @JSImport("node:path", JSImport.Namespace)
object path extends js.Object:
  def dirname(path: String): String = js.native

given ExecutionContext = JSExecutionContext.queue

// GitHub passes `with:` values as INPUT_<NAME>; running locally, the defaults apply.
def input(name: String, default: String): String =
  Node.process.env
    .selectDynamic(s"INPUT_${name.toUpperCase}")
    .asInstanceOf[js.UndefOr[String]]
    .filter(_.nonEmpty)
    .getOrElse(default)

def scrape(
    url: String,
    parser: String => Either[Throwable, List[Event]]
): Future[List[Event]] =
  val events = for
    res <- Node.fetch(url).toFuture
    _ <-
      if res.ok then Future.unit
      else Future.failed(Exception(s"HTTP ${res.status}"))
    html <- res.text().toFuture
    events <- Future.fromTry(parser(html).toTry)
  yield events
  // One broken source shouldn't drop the others: log it and carry on.
  events.recover { case e =>
    println(s"::warning::$url: ${e.getMessage}")
    Nil
  }

object index:
  def main(args: Array[String]): Unit =
    val sourcesPath = input("sources", "sources.json")
    val output = input("output", "data/events.json")
    val tz = input("timezone", "Europe/Rome")

    val events = for
      sources <- Future.fromTry(
        decode[Sources](fs.readFileSync(sourcesPath, "utf8")).toTry
      )
      scraped <- Future.sequence(
        sources.meetup.map(url =>
          scrape(s"${url.stripSuffix("/")}/events/", meetup)
        ) ++
          sources.luma.map(scrape(_, luma)) ++
          sources.eventbrite.map(scrape(_, eventbrite))
      )
    yield normalize(scraped.flatten ++ sources.custom, tz, js.Date.now())

    events.onComplete {
      case Success(events) =>
        fs.mkdirSync(path.dirname(output), js.Dynamic.literal(recursive = true))
        fs.writeFileSync(
          output,
          Printer.spaces2
            .copy(dropNullValues = true, colonLeft = "")
            .print(events.asJson) + "\n"
        )
        println(s"Wrote ${events.size} events to $output")
      case Failure(e) =>
        println(s"::error::${e.getMessage}")
        Node.process.exitCode = 1
    }
