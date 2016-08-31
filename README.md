# LPE Customer (Excel) Forecast Uploader

Parses customer forecasts received as Excel spreadsheets, compares them
against the entries for the forecasts stored under a project in IFS, and
updates IFS with the differences.

## Running

You’ll need to provide the database username and password under which to
run. This can be done in a number of ways:

* As `ENV`ironment variables: `DATABASE_USER` and `DATABASE_PASSWORD`.
* In a configuration file.

  A minimal configuration file containing just these options would look
like:

      {:database {:user "dortiz" :password "secret"}}

  Save this in `.secrets.edn` in the project root directory for it to be
used during system configuration at the REPL.

With this configuration in place you’re ready to run a REPL (yes,
a REPL, this isn’t quite fully baked enough for anything better):

    lein repl

Provided you’ve performed the database configuration as above you should
be able to start things up:

    user=> (start)
    :started

You’ll want to load a spreadsheet from disk:

```clojure
(def wb (spreadsheet/load-workbook "path/to/workbook.xlsx"))
```

But we don’t want the work_book_ we want the work_sheet_ containing the
forecast, assuming we have an SLB forecast let’s grab the first sheet
that looks sensible:

```clojure
(def ws (some #(when (cefu.forecast.slb/forecast-sheet? %) %) wb))
```

This is no good without something to compare it to, so let’s get the
current forecast from IFS:

```clojure
(def fc (cefu.forecast.ifs/forecast-map
         (:database reloaded.repl/system) "project-id"))
```

Some lookup tables from the database are also required:

```clojure
(def dlt (cefu.dates/substitute-table (:database reloaded.repl/system)))
(def plt (cefu.parts/lookup-table (:database reloaded.repl/system)))
```

Now we can fixup the customer forecast and do a like-for-like comparison:

```clojure
(let [d->s (fn [d] (clj-time.format/unparse
                    (clj-time.format/formatters :date) d))
      customer-fc
      (->> ;; parse the worksheet into a forecast map
           (cefu.forecast.slb/sheet->forecast ws)
           ;; replace parts with records including an ID
           (fc/rekey-parts #(plt (:part/number %) %))
           ;; ensure all forecast demands fall on working days
           (fc/rekey-dates
            #(let [wd (cefu.dates/closest-week-day-in-month %)]
               (dlt wd wd))))
      [bad-parts good-parts] (cefu.forecast/group-by-ided customer-fc)
      ;; we only want to compare/update sub-projects on the customer forecast
      fc (select-keys fc (set (keys customer-fc)))
      d (cefu.forecast/diff good-parts fc)]
  (when (seq bad-parts)
    (println "Parts couldn't be ID'd:")
    (pprint bad-parts))
  (println "Diff results:")
  (pprint
   (map (fn [[op spid part date q]]
          [op spid (:part/contract part) (:part/id part) (d->s date) q])
        (cefu.forecast/crud-ops d))))
```

`cefu.forecast/crud-ops` turns the lists of differences into a single
list of database update operations which is both a little easier to read
and can be fed directly into an update fn:

```clojure
(cefu.forecast.ifs/update-project
 (:connection (:database reloaded.repl/system))
 "project-id"
 (cefu.forecast/crud-ops diff-results))
```

## License

Copyright © 2016 Lymington Precision Engineers Co. Ltd.

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
