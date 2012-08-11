package eventstore
package redis

import org.joda.time.DateTimeUtils
import play.api.Logger
import play.api.libs.json._
import _root_.redis.clients.jedis._
import scala.collection.JavaConverters._

trait RedisLuaEventCommitter[Event] { this: RedisEventStore[Event] =>
  protected[this] def eventFormat: Format[Event]

  private[this] val TryCommitScript: String = """
    | local commitsKey = KEYS[1]
    | local streamKey = KEYS[2]
    | local timestamp = tonumber(ARGV[1])
    | local streamId = ARGV[2]
    | local expected = tonumber(ARGV[3])
    | local events = ARGV[4]
    |
    | local actual = tonumber(redis.call('llen', streamKey))
    | if actual ~= expected then
    |   return {'conflict', tostring(actual)}
    | end
    |
    | local storeRevision = tonumber(redis.call('hlen', commitsKey))
    | local commitId = storeRevision + 1
    | local commitData = string.format('{"storeRevision":%d,"timestamp":%d,"streamId":%s,"streamRevision":%d,"events":%s}',
    |   commitId, timestamp, cjson.encode(streamId), actual + 1, events)
    |
    | redis.call('hset', commitsKey, commitId, commitData)
    | redis.call('rpush', streamKey, commitId)
    | redis.call('publish', commitsKey, commitData)
    |
    | return {'commit', tostring(commitId)}
    """.stripMargin

  private[this] val TryCommitScriptId = withJedis { _.scriptLoad(TryCommitScript) }
  Logger.debug("Redis Lua TryCommitScript loaded with id " + TryCommitScriptId)

  object committer extends EventCommitter[Event] {
    override def tryCommit(append: Update[Event]): CommitResult[Event] = {
      val timestamp = DateTimeUtils.currentTimeMillis
      val serializedEvents = Json.stringify(Json.toJson(Seq(append.event))(Writes.seqWrites(eventFormat)))

      val response = withJedis { _.evalsha(TryCommitScriptId, 2,
        /* KEYS */ CommitsKey, keyForStream(append.streamId),
        /* ARGV */ timestamp.toString, append.streamId, append.expected.value.toString, serializedEvents)
      }

      try {
        response.asInstanceOf[java.util.List[_]].asScala match {
          case Seq("conflict", actual: String) =>
            val conflicting = reader.readStream(append.streamId, since = append.expected)
            Left(Conflict(append.streamId, StreamRevision(actual.toLong), append.expected, conflicting))
          case Seq("commit", storeRevision: String) =>
            Right(Commit(StoreRevision(storeRevision.toLong), timestamp, append.streamId, append.expected.next, Seq(append.event)))
        }
      } catch {
        case e: Exception =>
          throw new EventStoreException("Error parsing response from Redis TryCommit script: " + response, e)
      }
    }
  }
}
