# events-parsing

A GitHub Action, written in Scala.js, that collects upcoming events from Meetup groups, Luma calendars and Eventbrite organizers, plus custom ones, into a single JSON file.

```yaml
- uses: TonioGela/events-parsing@main
  with:
    sources: sources.json    # default
    output: data/events.json # default
    timezone: Europe/Rome    # default
```

`sources.json` structure, every key is optional:

```json
{
  "meetup": ["https://www.meetup.com/foo-bar/"],
  "luma": ["https://luma.com/foo-bar"],
  "eventbrite": ["https://www.eventbrite.com/o/foo-bar-121353500517"],
  "custom": [
    {
      "meetupName": "Foo Bar",
      "url": "https://www.foo.bar/",
      "date": "2026-10-20T09:30:00+02:00",
      "dateTo": "2026-10-23T18:00:00+02:00",
      "title": "Workshop",
      "venue": "Foo Bar Labs"
    }
  ]
}
```

Each output event has `meetupName`, `url`, `date`, `title`, `venue`, and `dateTo` when it spans more than one day. Events that ended before today (in `timezone`) are dropped, and the rest are sorted by date.

It runs locally too: `node index.js` reads `sources.json` and writes `data/events.json`, and the same `INPUT_SOURCES`, `INPUT_OUTPUT` and `INPUT_TIMEZONE` environment variables override them.

## Development

```sh
scala-cli test .                              # parsers against the pages saved in test/fixtures/
scala-cli --power package . -o index.js -f    # index.js is committed: CI checks it is up to date
```

All three sites embed their page state in a `__NEXT_DATA__` script, so each parser decodes that JSON. To refresh a fixture, save the page again and update the expected events in `test/events.scala`.
