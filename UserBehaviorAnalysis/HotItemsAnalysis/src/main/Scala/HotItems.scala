
import java.sql.Timestamp
import java.util.Properties

import org.apache.flink.api.common.functions.AggregateFunction
import org.apache.flink.api.common.serialization.SimpleStringSchema
import org.apache.flink.api.common.state.{ListState, ListStateDescriptor}
import org.apache.flink.api.java.tuple.{Tuple, Tuple1}
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.TimeCharacteristic
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.function.WindowFunction
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer
import org.apache.flink.util.Collector

import scala.collection.mutable.ListBuffer


case class UserBehavior(userId: Long, itemId: Long, categoryId: Int, behavior: String, timestamp: Long)
case class ItemViewCount(itemId: Long, windowEnd: Long, count: Long)

object HotItems {
  def main(args: Array[String]): Unit = {
    val properties = new Properties()
    properties.setProperty("bootstrap.servers", "localhost:9092")
    properties.setProperty("group.id", "consumer-group")
    properties.setProperty("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    properties.setProperty("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
    properties.setProperty("auto.offset.reset", "latest")

    //Create an environment
    val env = StreamExecutionEnvironment.getExecutionEnvironment
    //Define the type of time
    env.setStreamTimeCharacteristic(TimeCharacteristic.EventTime)
    env.setParallelism(1)
    val stream = env
      .readTextFile("/Users/xiaohuanggua/git/BigData/UserBehaviorAnalysis/HotItemsAnalysis/src/main/resources/UserBehavior.csv")
      //.addSource( new FlinkKafkaConsumer[String]("hotitems", new SimpleStringSchema(), properties) )
      .map(line => {
        val linearray = line.split(",")
        UserBehavior(linearray(0).toLong, linearray(1).toLong,linearray(2).toInt, linearray(3),linearray(4).toLong)
      })
      //Assign timestamps and watermark
      .assignAscendingTimestamps(_.timestamp * 1000)
      .filter(_.behavior == "pv")
      .keyBy("itemId")
      .timeWindow(Time.hours(1), Time.minutes(5))
      .aggregate(new CountAgg(), new WindowResultFunction())
      .keyBy("windowEnd")
      .process(new TopNHotItems(3))
      .print
    env.execute("Hot Items Job")

  }

  class CountAgg extends AggregateFunction[UserBehavior, Long, Long]{
    override def createAccumulator(): Long = 0L

    override def add(in: UserBehavior, acc: Long): Long = acc + 1

    override def getResult(acc: Long): Long = acc

    override def merge(acc: Long, acc1: Long): Long = acc + acc1
  }
  //Define a class extends WindowFunction for generating specific type of output
  class WindowResultFunction extends WindowFunction[Long, ItemViewCount, Tuple, TimeWindow]{
    override def apply(key: Tuple, window: TimeWindow, input: Iterable[Long], out: Collector[ItemViewCount]): Unit = {
      val itemId: Long = key.asInstanceOf[Tuple1[Long]].f0
      val count = input.iterator.next()
      out.collect(ItemViewCount(itemId, window.getEnd, count))
    }
  }


  class TopNHotItems(topSize: Int) extends KeyedProcessFunction[Tuple,ItemViewCount,String]{
    private var itemState: ListState[ItemViewCount] = _

    override def open(parameters: Configuration): Unit = {
      super.open(parameters)
      val itemStateDesc = new ListStateDescriptor[ItemViewCount]("itemState",classOf[ItemViewCount])
      itemState = getRuntimeContext.getListState(itemStateDesc)
    }

    override def processElement(i: ItemViewCount, context: KeyedProcessFunction[Tuple, ItemViewCount, String]#Context, collector: Collector[String]): Unit = {
      itemState.add(i)
      //Define the trigger as windowEnd + 1
      context.timerService.registerEventTimeTimer(i.windowEnd + 1)
    }

    override def onTimer(timestamp: Long, ctx: KeyedProcessFunction[Tuple, ItemViewCount, String]#OnTimerContext, out: Collector[String]): Unit = {
      val allItems: ListBuffer[ItemViewCount] = ListBuffer()
      import  scala.collection.JavaConversions._
      for(item <- itemState.get){
        allItems += item
      }
      itemState.clear()
      //Sorting
      val sortedItems = allItems.sortBy(_.count)(Ordering.Long.reverse).take(topSize)
      //Reformat the sorted data for printing
      val result: StringBuilder = new StringBuilder
      result.append("============================\n")
      result.append("时间：").append(new Timestamp(timestamp - 1)).append("\n")
      for(i <- sortedItems.indices){
        val currentItem: ItemViewCount = sortedItems(i)
        result.append("No").append(i+1).append(":")
          .append(" ID =").append(currentItem.itemId)
          .append(" view =").append(currentItem.count).append("\n")
      }
      result.append("=============================\n\n")
      Thread.sleep(100)
      out.collect(result.toString())
    }
  }

}