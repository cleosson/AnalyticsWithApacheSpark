// --driver-memory 8G
// Because of the spark-shell
val sparkSession = spark

val path = sys.env("AAS_DATA_PATH")
val csvPath = path + sys.env("AAS_CSV_FILE")

val taxiRaw = sparkSession.read.option("header", "true").csv(csvPath)

/* ... new cell ... */

taxiRaw.take(4)

/* ... new cell ... */

case class Trip(
  pickupTime: Long,
  dropoffTime: Long,
  passengerCount: Int,
  tripDistance: Double,
  pickupX: Double,
  pickupY: Double,
  dropoffX: Double,
  dropoffY: Double,
  totalAmount: Double
)

/* ... new cell ... */

class RichRow(row: org.apache.spark.sql.Row) {
  def getAs[T](field: String): Option[T] = {
    if (row.isNullAt(row.fieldIndex(field))) {
      None
    } else {
      Some(row.getAs[T](field))
    }
  }
}

/* ... new cell ... */

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.Instant

def stringToUnixEpochMilliseconds(rr: RichRow, timeField: String): Long = {
    val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")
    val optDt = rr.getAs[String](timeField) 
    optDt.map(dt => Instant.from(formatter.parse(dt + " -0300")).toEpochMilli).getOrElse(0L)
}

/* ... new cell ... */

def stringToDouble(rr: RichRow, locField: String): Double = {
  rr.getAs[String](locField).map(_.toDouble).getOrElse(0.0)
}

/* ... new cell ... */

def stringToInt(rr: RichRow, locField: String): Int = {
  rr.getAs[String](locField).map(_.toInt).getOrElse(0)
}

/* ... new cell ... */

def parse(row: org.apache.spark.sql.Row): Trip = {
  val rr = new RichRow(row)
  Trip(
    pickupTime = stringToUnixEpochMilliseconds(rr, "tpep_pickup_datetime"),
    dropoffTime = stringToUnixEpochMilliseconds(rr, "tpep_dropoff_datetime"),
    passengerCount = stringToInt(rr, "passenger_count"),
    tripDistance = stringToDouble(rr, "trip_distance"),
    pickupX = stringToDouble(rr, "pickup_longitude"),
    pickupY = stringToDouble(rr, "pickup_latitude"),
    dropoffX = stringToDouble(rr, "dropoff_longitude"),
    dropoffY = stringToDouble(rr, "dropoff_latitude"),
    totalAmount = stringToDouble(rr, "total_amount")
  )
}

/* ... new cell ... */

val taxiRawSample = taxiRaw.sample(false, 0.0001d)
val taxiRawSampleTrip = taxiRawSample.map(parse)
taxiRawSampleTrip.take(4)

/* ... new cell ... */

def safe[S, T](f: S => T): S => Either[T, (S, Exception)] = {
  new Function[S, Either[T, (S, Exception)]] with Serializable {
    def apply(s: S): Either[T, (S, Exception)] = {
      try {
        Left(f(s))
      } catch {
        case e: Exception => Right((s, e))
      }
    }
  }
}

/* ... new cell ... */

val safeParse = safe(parse)
val taxiParsed = taxiRaw.rdd.map(safeParse)

/* ... new cell ... */

taxiParsed.map(_.isLeft).countByValue().foreach(println)

/* ... new cell ... */

val taxiGood = taxiParsed.map(_.left.get).toDS
taxiGood.cache()

/* ... new cell ... */

import java.util.concurrent.TimeUnit

val hours = (pickup: Long, dropoff: Long) => {
  TimeUnit.HOURS.convert(dropoff - pickup, TimeUnit.MILLISECONDS)
}

/* ... new cell ... */

hours(1,0)

/* ... new cell ... */

val hours = (pickup: Long, dropoff: Long) => {
  val retVal = TimeUnit.HOURS.convert(dropoff - pickup, TimeUnit.MILLISECONDS)
  if (dropoff - pickup < 0 && retVal == 0) {
    -1
  } else {
    retVal
  }
}

/* ... new cell ... */

hours(1,0)

/* ... new cell ... */

import org.apache.spark.sql.functions.udf

val hoursUDF = udf(hours)

/* ... new cell ... */

taxiGood.
  groupBy(hoursUDF($"pickupTime", $"dropoffTime").as("hours")).
  count().
  sort("hours").
  collect()

/* ... new cell ... */

taxiGood.
  groupBy(hoursUDF($"pickupTime", $"dropoffTime").as("hours")).
  count().
  where($"hours" > 1).
  sort("hours").
  collect()

/* ... new cell ... */

taxiGood.
  groupBy(hoursUDF($"pickupTime", $"dropoffTime").as("hours")).
  count().
  where($"hours" < 0).
  sort("hours").
  collect()

/* ... new cell ... */

taxiGood.
  groupBy(hoursUDF($"pickupTime", $"dropoffTime").as("hours")).
  agg(mean($"totalAmount"), mean($"tripDistance")).
  sort("hours").
  collect()

/* ... new cell ... */

spark.udf.register("hours", hours)
val taxiClean = taxiGood.where(
  "hours(pickupTime, dropoffTime) BETWEEN 0 AND 3"
)

taxiClean.collect

/* ... new cell ... */

val taxiCleanXY = taxiClean.filter(trip => trip.pickupX != 0 && trip.pickupY != 0 && trip.dropoffX != 0 && trip.dropoffY != 0)
taxiCleanXY.collect

/* ... new cell ... */

case class Heatmap(
  dy: Int,
  ts: Long,
  ln: Double,
  lt: Double,
  ct: Int
)

/* ... new cell ... */

var taxiCleanPickup = taxiCleanXY.map(trip => Heatmap(Instant.ofEpochMilli(trip.pickupTime).atZone(java.time.ZoneId.of("UTC-0300")).getDayOfYear, trip.pickupTime, trip.pickupX, trip.pickupY, 1))
val sessionsPickup = taxiCleanPickup.repartition($"dy").sortWithinPartitions($"dy", $"ts")
sessionsPickup.write.json(path + "sessionsPickup")

/* ... new cell ... */

var taxiCleanDropoff = taxiCleanXY.map(trip => Heatmap(Instant.ofEpochMilli(trip.dropoffTime).atZone(java.time.ZoneId.of("UTC-0300")).getDayOfYear, trip.dropoffTime, trip.dropoffX, trip.dropoffY, 1))
val sessionsDropoff = taxiCleanDropoff.repartition($"dy").sortWithinPartitions($"dy", $"ts")
sessionsDropoff.write.json(path + "sessionsDropoff")

                  